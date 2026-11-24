/*
 * asr-ctl — a loopback control socket for the on-device ASR daemons.
 *
 * Why this exists: the bridge app runs as an app uid, and Android (API 29+)
 * forbids it from exec'ing binaries that live outside its own APK, so the app
 * can watch a service port but can never start or stop the daemon behind it.
 * The daemons must run as the shell user. This little server is the missing
 * half: it runs as the shell user too, listens on 127.0.0.1 only, and does the
 * one thing the app cannot — spawn and kill the daemons.
 *
 * Protocol: plain HTTP/1.0, one request per connection, `Connection: close`.
 *
 *   GET /status            -> {"ok":true,"services":[{name,port,up,pid,model}]}
 *   GET /start?name=base   -> {"ok":true,"name":"base","started":true,"ready":true}
 *   GET /stop?name=base    -> {"ok":true,"name":"base","stopped":true}
 *
 * Anything else answers 400 with {"ok":false,"error":"..."}.
 *
 * The service list is not hardcoded: it is read from $ASR_CTL_CONF
 * (default /data/local/tmp/ct2/asr-services.conf), one service per line:
 *
 *   # name port model                          beam threads compute min_speech
 *   base   18125 /data/local/tmp/ct2/model-base 5    6       int8    0.25
 *   small  18126 /data/local/tmp/ct2/model       5    6       int8    0.25
 *
 * Only the first three fields are required. Everything is loopback, so no token:
 * the socket is reachable by anything on the device that can open 127.0.0.1,
 * which is exactly the same audience the daemons already have.
 */

#define _GNU_SOURCE
#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define VERSION "1.0"
#define MAX_SERVICES 16
#define NAME_LEN 32
#define PATH_LEN 256
#define REQ_LEN 1024
#define RESP_LEN 8192

#define DEFAULT_CONF "/data/local/tmp/ct2/asr-services.conf"
#define DEFAULT_BIN "/data/local/tmp/ct2/whisper_ct2_arm64"
#define DEFAULT_MEL "/data/local/tmp/ct2/mel80.bin"
#define DEFAULT_ROOT "/data/local/tmp/ct2"

/* How long one /start may spend waiting for the new daemon to answer. */
#define START_WAIT_MS 6000
/* How long /stop waits for SIGTERM before escalating to SIGKILL. */
#define STOP_WAIT_MS 3000

typedef struct {
    char name[NAME_LEN];
    int port;
    char model[PATH_LEN];
    char beam[16];
    char threads[8];
    char compute[16];
    char min_speech[8];
} service_t;

static service_t services[MAX_SERVICES];
static int service_count;

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void copy_str(char *dst, size_t size, const char *src) {
    snprintf(dst, size, "%s", src ? src : "");
}

/* Can anything accept a connection on this loopback port? */
static int port_open(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return 0;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short) port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int open = connect(fd, (struct sockaddr *) &addr, sizeof(addr)) == 0;
    close(fd);
    return open;
}

/*
 * The pid of the daemon serving `port`, or -1. `pidof` alone is not enough:
 * every daemon shares the binary name, so the port has to be read back from the
 * command line. Android truncates `ps -o CMD`, hence /proc/<pid>/cmdline.
 */
static pid_t find_pid(int port) {
    char want[16];
    snprintf(want, sizeof(want), "%d", port);
    DIR *proc = opendir("/proc");
    if (!proc) return -1;
    pid_t found = -1;
    struct dirent *entry;
    while ((entry = readdir(proc)) != NULL) {
        if (!isdigit((unsigned char) entry->d_name[0])) continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%s/cmdline", entry->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char buffer[512];
        ssize_t length = read(fd, buffer, sizeof(buffer) - 1);
        close(fd);
        if (length <= 0) continue;
        buffer[length] = '\0';

        /*
         * Split the raw cmdline into argv. Matching tokens, not the flattened
         * string, is what keeps `/system/bin/sh -c "… whisper_ct2_arm64 --serve
         * 18125 …"` from being mistaken for the daemon: its argv[0] is sh.
         */
        char *args[16];
        int count = 0;
        char *cursor = buffer;
        while (cursor < buffer + length && count < 16) {
            args[count++] = cursor;
            cursor += strlen(cursor) + 1;
        }
        if (count < 2) continue;

        const char *binary = strrchr(args[0], '/');
        binary = binary ? binary + 1 : args[0];
        if (strcmp(binary, "whisper_ct2_arm64") != 0) continue;

        for (int i = 1; i + 1 < count; i++) {
            if (strcmp(args[i], "--serve") == 0 && strcmp(args[i + 1], want) == 0) {
                found = (pid_t) atoi(entry->d_name);
                break;
            }
        }
        if (found > 0) break;
    }
    closedir(proc);
    return found;
}

static service_t *service_named(const char *name) {
    if (!name || !*name) return NULL;
    for (int i = 0; i < service_count; i++) {
        if (strcmp(services[i].name, name) == 0) return &services[i];
    }
    return NULL;
}

static void trim(char *value) {
    char *start = value;
    while (*start && isspace((unsigned char) *start)) start++;
    if (start != value) memmove(value, start, strlen(start) + 1);
    size_t length = strlen(value);
    while (length > 0 && isspace((unsigned char) value[length - 1])) value[--length] = '\0';
}

static int load_conf(const char *path) {
    service_count = 0;
    FILE *file = fopen(path, "r");
    if (!file) return -1;
    char line[PATH_LEN + 128];
    while (fgets(line, sizeof(line), file) != NULL && service_count < MAX_SERVICES) {
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';
        trim(line);
        if (!*line) continue;
        char name[NAME_LEN], model[PATH_LEN], beam[16] = "5", threads[8] = "6";
        char compute[16] = "int8", min_speech[8] = "0.25";
        int port = 0;
        int fields = sscanf(line, "%31s %d %255s %15s %7s %15s %7s",
                            name, &port, model, beam, threads, compute, min_speech);
        if (fields < 3 || port <= 0 || port > 65535) continue;
        service_t *service = &services[service_count++];
        memset(service, 0, sizeof(*service));
        copy_str(service->name, sizeof(service->name), name);
        copy_str(service->model, sizeof(service->model), model);
        copy_str(service->beam, sizeof(service->beam), beam);
        copy_str(service->threads, sizeof(service->threads), threads);
        copy_str(service->compute, sizeof(service->compute), compute);
        copy_str(service->min_speech, sizeof(service->min_speech), min_speech);
        service->port = port;
    }
    fclose(file);
    return service_count;
}

/*
 * Spawn one daemon. Double fork so the daemon is reparented to init and this
 * server never carries a zombie around; the intermediate child is reaped here.
 */
static pid_t spawn_daemon(service_t *service) {
    const char *bin = getenv("ASR_CTL_BIN");
    if (!bin || !*bin) bin = DEFAULT_BIN;
    const char *mel = getenv("ASR_CTL_MEL");
    if (!mel || !*mel) mel = DEFAULT_MEL;

    char log_path[PATH_LEN];
    snprintf(log_path, sizeof(log_path), "%s/serve-%d.log", DEFAULT_ROOT, service->port);

    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        setsid();
        pid_t grandchild = fork();
        if (grandchild < 0) _exit(1);
        if (grandchild > 0) _exit(0);

        if (chdir(DEFAULT_ROOT) != 0) { /* keep going: absolute paths are used */ }
        setenv("LD_LIBRARY_PATH", DEFAULT_ROOT, 1);

        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) { dup2(devnull, STDIN_FILENO); close(devnull); }
        int log = open(log_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (log >= 0) {
            dup2(log, STDOUT_FILENO);
            dup2(log, STDERR_FILENO);
            close(log);
        }

        char port[16];
        snprintf(port, sizeof(port), "%d", service->port);
        execl(bin, bin, "--serve", port, service->model, mel,
              service->beam, service->threads, service->compute, service->min_speech,
              (char *) NULL);
        _exit(127);
    }

    int status = 0;
    waitpid(child, &status, 0);
    /* The daemon needs a moment before it shows up in /proc. */
    for (int i = 0; i < 40; i++) {
        pid_t pid = find_pid(service->port);
        if (pid > 0) return pid;
        struct timespec pause = {0, 25 * 1000 * 1000};
        nanosleep(&pause, NULL);
    }
    return -1;
}

static int wait_ready(int port, long budget_ms) {
    long deadline = now_ms() + budget_ms;
    while (now_ms() < deadline) {
        if (port_open(port)) return 1;
        struct timespec pause = {0, 100 * 1000 * 1000};
        nanosleep(&pause, NULL);
    }
    return port_open(port);
}

static int stop_pid(pid_t pid, int port) {
    if (kill(pid, SIGTERM) != 0 && errno != ESRCH) return -1;
    long deadline = now_ms() + STOP_WAIT_MS;
    while (now_ms() < deadline) {
        if (kill(pid, 0) != 0 && errno == ESRCH) return 0;
        struct timespec pause = {0, 100 * 1000 * 1000};
        nanosleep(&pause, NULL);
    }
    kill(pid, SIGKILL);
    deadline = now_ms() + 1500;
    while (now_ms() < deadline) {
        if (kill(pid, 0) != 0 && errno == ESRCH) return 0;
        struct timespec pause = {0, 100 * 1000 * 1000};
        nanosleep(&pause, NULL);
    }
    /* The process is gone even if the port has not drained yet. */
    return find_pid(port) < 0 ? 0 : -1;
}

/* --------------------------------------------------------------- HTTP plumbing */

static void http_reply(int fd, int code, const char *body) {
    char header[256];
    const char *reason = code == 200 ? "OK" : code == 404 ? "Not Found" : "Bad Request";
    int length = (int) strlen(body);
    snprintf(header, sizeof(header),
             "HTTP/1.1 %d %s\r\nContent-Type: application/json\r\n"
             "Content-Length: %d\r\nConnection: close\r\n\r\n",
             code, reason, length);
    write(fd, header, strlen(header));
    write(fd, body, length);
}

static int json_escape(const char *input, char *out, size_t size) {
    size_t used = 0;
    for (const char *p = input; *p && used + 2 < size; p++) {
        if (*p == '"' || *p == '\\') {
            out[used++] = '\\';
            out[used++] = *p;
        } else if ((unsigned char) *p < 0x20) {
            out[used++] = ' ';
        } else {
            out[used++] = *p;
        }
    }
    out[used] = '\0';
    return (int) used;
}

static void status_json(char *out, size_t size) {
    size_t used = 0;
    used += (size_t) snprintf(out + used, size - used, "{\"ok\":true,\"controller\":\"asr-ctl\",\"version\":\"%s\",\"services\":[", VERSION);
    for (int i = 0; i < service_count; i++) {
        service_t *service = &services[i];
        int up = port_open(service->port);
        pid_t pid = up ? find_pid(service->port) : find_pid(service->port);
        char model[PATH_LEN * 2];
        json_escape(service->model, model, sizeof(model));
        used += (size_t) snprintf(out + used, size - used,
                                  "%s{\"name\":\"%s\",\"port\":%d,\"up\":%s,\"pid\":%ld,\"model\":\"%s\"}",
                                  i == 0 ? "" : ",", service->name, service->port,
                                  up ? "true" : "false", (long) pid, model);
        if (used >= size - 2) break;
    }
    snprintf(out + used, size - used, "]}");
}

/* Percent-decode one query value into `out`. */
static void query_value(const char *query, const char *key, char *out, size_t size) {
    out[0] = '\0';
    if (!query) return;
    size_t key_len = strlen(key);
    const char *p = query;
    while (p && *p) {
        if (strncmp(p, key, key_len) == 0 && p[key_len] == '=') {
            p += key_len + 1;
            size_t used = 0;
            while (*p && *p != '&' && used + 1 < size) {
                if (*p == '%' && isxdigit((unsigned char) p[1]) && isxdigit((unsigned char) p[2])) {
                    char hex[3] = {p[1], p[2], 0};
                    out[used++] = (char) strtol(hex, NULL, 16);
                    p += 3;
                } else if (*p == '+') {
                    out[used++] = ' ';
                    p++;
                } else {
                    out[used++] = *p++;
                }
            }
            out[used] = '\0';
            return;
        }
        p = strchr(p, '&');
        if (p) p++;
    }
}

static void handle(int fd, const char *request_line) {
    char body[RESP_LEN];
    char path[REQ_LEN];
    char query[REQ_LEN];
    path[0] = '\0';
    query[0] = '\0';

    /* "GET /status?x=1 HTTP/1.1" -> path + query */
    const char *start = strchr(request_line, ' ');
    if (!start) { http_reply(fd, 400, "{\"ok\":false,\"error\":\"bad request line\"}"); return; }
    start++;
    const char *end = strchr(start, ' ');
    if (!end) end = start + strlen(start);
    size_t length = (size_t) (end - start);
    if (length >= sizeof(path)) length = sizeof(path) - 1;
    memcpy(path, start, length);
    path[length] = '\0';
    char *mark = strchr(path, '?');
    if (mark) {
        snprintf(query, sizeof(query), "%s", mark + 1);
        *mark = '\0';
    }

    if (strcmp(path, "/status") == 0 || strcmp(path, "/health") == 0) {
        status_json(body, sizeof(body));
        http_reply(fd, 200, body);
        return;
    }

    if (strcmp(path, "/start") == 0 || strcmp(path, "/stop") == 0) {
        char name[NAME_LEN];
        query_value(query, "name", name, sizeof(name));
        service_t *service = service_named(name);
        if (!service) {
            snprintf(body, sizeof(body),
                     "{\"ok\":false,\"error\":\"unknown service\",\"name\":\"%s\",\"known\":[", name);
            size_t used = strlen(body);
            for (int i = 0; i < service_count && used + NAME_LEN + 8 < sizeof(body); i++) {
                used += (size_t) snprintf(body + used, sizeof(body) - used, "%s\"%s\"",
                                          i == 0 ? "" : ",", services[i].name);
            }
            snprintf(body + used, sizeof(body) - used, "]}");
            http_reply(fd, 404, body);
            return;
        }
        int up = port_open(service->port);
        pid_t pid = find_pid(service->port);
        if (path[1] == 's' && path[2] == 't' && path[3] == 'a') {
            /* /start */
            if (up) {
                snprintf(body, sizeof(body),
                         "{\"ok\":true,\"name\":\"%s\",\"port\":%d,\"started\":false,\"already\":true,"
                         "\"ready\":true,\"pid\":%ld}", service->name, service->port, (long) pid);
                http_reply(fd, 200, body);
                return;
            }
            pid_t spawned = spawn_daemon(service);
            int ready = spawned > 0 && wait_ready(service->port, START_WAIT_MS);
            snprintf(body, sizeof(body),
                     "{\"ok\":%s,\"name\":\"%s\",\"port\":%d,\"started\":%s,\"ready\":%s,\"pid\":%ld%s%s}",
                     spawned > 0 ? "true" : "false", service->name, service->port,
                     spawned > 0 ? "true" : "false", ready ? "true" : "false", (long) spawned,
                     spawned > 0 ? "" : ",\"error\":\"cannot spawn the daemon\",\"log\":\"serve-",
                     spawned > 0 ? "" : "");
            http_reply(fd, spawned > 0 ? 200 : 500, body);
            return;
        }
        /* /stop */
        if (!up && pid <= 0) {
            snprintf(body, sizeof(body),
                     "{\"ok\":true,\"name\":\"%s\",\"port\":%d,\"stopped\":false,\"already\":true}",
                     service->name, service->port);
            http_reply(fd, 200, body);
            return;
        }
        int stopped = pid > 0 ? stop_pid(pid, service->port) : 0;
        snprintf(body, sizeof(body),
                 "{\"ok\":%s,\"name\":\"%s\",\"port\":%d,\"stopped\":%s,\"pid\":%ld}",
                 stopped == 0 ? "true" : "false", service->name, service->port,
                 stopped == 0 ? "true" : "false", (long) pid);
        http_reply(fd, stopped == 0 ? 200 : 500, body);
        return;
    }

    snprintf(body, sizeof(body),
             "{\"ok\":false,\"error\":\"unknown endpoint\",\"path\":\"%s\","
             "\"endpoints\":[\"/status\",\"/start?name=\",\"/stop?name=\"]}", path);
    http_reply(fd, 400, body);
}

int main(int argc, char **argv) {
    int port = 18124;
    const char *conf = getenv("ASR_CTL_CONF");
    if (!conf || !*conf) conf = DEFAULT_CONF;
    if (argc > 1) port = atoi(argv[1]);
    if (port <= 0 || port > 65535) {
        fprintf(stderr, "usage: %s [port]   (conf: $ASR_CTL_CONF or %s)\n", argv[0], DEFAULT_CONF);
        return 2;
    }

    /* Do not die because the app closed a connection early. */
    signal(SIGPIPE, SIG_IGN);

    if (load_conf(conf) < 0) {
        fprintf(stderr, "asr-ctl: cannot read %s\n", conf);
        return 1;
    }
    if (service_count == 0) {
        fprintf(stderr, "asr-ctl: %s lists no service\n", conf);
        return 1;
    }

    int listener = socket(AF_INET, SOCK_STREAM, 0);
    if (listener < 0) { perror("socket"); return 1; }
    /* The daemons are forked from here. A listener that survives into a child keeps
     * port 18124 bound after this controller is replaced: the new one cannot bind,
     * and the old socket accepts nothing, so the buttons lose their controller. */
    fcntl(listener, F_SETFD, FD_CLOEXEC);
    int yes = 1;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short) port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(listener, (struct sockaddr *) &addr, sizeof(addr)) != 0) {
        perror("bind");
        close(listener);
        return 1;
    }
    if (listen(listener, 8) != 0) { perror("listen"); close(listener); return 1; }

    fprintf(stdout, "ready: asr-ctl %s on 127.0.0.1:%d, %d service(s) from %s\n",
            VERSION, port, service_count, conf);
    fflush(stdout);

    for (;;) {
        int fd = accept(listener, NULL, NULL);
        if (fd < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            continue;
        }
        /* /start spawns the daemon from this process. Without close-on-exec the child
         * inherits this client socket and the caller never sees EOF: it reads the JSON,
         * then blocks until its own timeout and calls the whole answer a failure. */
        fcntl(fd, F_SETFD, FD_CLOEXEC);
        char request[REQ_LEN];
        ssize_t length = recv(fd, request, sizeof(request) - 1, 0);
        if (length <= 0) { close(fd); continue; }
        request[length] = '\0';
        char *line_end = strpbrk(request, "\r\n");
        if (line_end) *line_end = '\0';
        handle(fd, request);
        close(fd);
    }
    return 0;
}
