"""Local-only RESP fault injector; never connects outside loopback."""
import http.server
import json
import socket
import socketserver
import threading

state = {"mode": "none", "events": [], "trace": []}
guard = threading.Lock()


def frame(stream):
    line = stream.readline()
    if not line:
        raise EOFError
    data = line
    if line[:1] == b"$" and int(line[1:]) >= 0:
        data += stream.read(int(line[1:]) + 2)
    elif line[:1] == b"*" and int(line[1:]) >= 0:
        for _ in range(int(line[1:])):
            data += frame(stream)
    return data


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(("127.0.0.1", 16379)) as upstream:
                local = self.request.makefile("rb")
                remote = upstream.makefile("rb")
                while True:
                    request = frame(local)
                    is_lock = b"placement:lock" in request and b"hincrby" in request
                    unlock = is_lock and b", -1)" in request
                    acquire = is_lock and not unlock
                    commit = b"echo-placement-commit-v2" in request
                    with guard:
                        mode = state["mode"]
                        hit = ((unlock and mode == "unlock-lost") or (acquire and mode == "acquire-reply-lost")
                               or (commit and mode in ("commit-lost", "commit-reply-lost", "commit-replay")))
                        if hit:
                            state["mode"] = "none"
                            state["events"].append(mode)
                    if hit and mode in ("unlock-lost", "commit-lost"):
                        print("INJECT request lost before Redis execution: " + mode, flush=True)
                        return
                    upstream.sendall(request)
                    response = frame(remote)
                    if hit and mode == "commit-replay":
                        upstream.sendall(request)
                        replay = frame(remote)
                        if replay != response:
                            raise AssertionError("Commit replay changed its receipt")
                    if is_lock or commit:
                        with guard:
                            state["trace"].append({"operation": "commit" if commit else "unlock" if unlock else "acquire", "response": repr(response), "reply_dropped": hit and mode != "commit-replay"})
                    if hit and mode != "commit-replay":
                        print("INJECT operation executed; response dropped: " + repr(response), flush=True)
                        return
                    self.request.sendall(response)
        except (EOFError, ConnectionError, OSError):
            pass


class Control(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with guard:
            if self.path.startswith("/arm/"):
                state["mode"] = self.path.removeprefix("/arm/")
                state["events"] = []
                state["trace"] = []
            body = json.dumps(state).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    threading.Thread(target=http.server.ThreadingHTTPServer(("127.0.0.1", 16381), Control).serve_forever, daemon=True).start()
    print("Lab proxy 127.0.0.1:16380 -> 127.0.0.1:16379; control 16381", flush=True)
    Server(("127.0.0.1", 16380), Relay).serve_forever()
