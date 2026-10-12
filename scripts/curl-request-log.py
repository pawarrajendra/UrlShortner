"""Log outgoing walkthrough requests from the actual curl arguments."""
from datetime import datetime, timezone
import json
import sys
from urllib.parse import urlsplit


def api_description(method, path):
    if path == "/health/live":
        return "Reports whether the application process is alive."
    if path == "/health/ready":
        return "Checks PostgreSQL connectivity and the expected migration history."
    if path == "/health/ingress":
        return "Checks NGINX itself; this is not aggregate replica readiness."
    if path.endswith("/stats"):
        return "Reads committed GET observations in UTC buckets; counts can lag."
    if path == "/api/v1/links":
        return "Creates an immutable short link, or replays its original keyed creation."
    if path.startswith("/api/v1/links/"):
        if method == "DELETE":
            return "Commits irreversible disable, then attempts cache invalidation; retains the row."
        return "Reads current link metadata, including expiry and disabled state."
    if path.startswith("/r/"):
        if method == "HEAD":
            return "Checks public redirect/lifecycle headers without a body or analytics event."
        if method == "GET":
            return "Resolves a public link; active links return 302 and submit best-effort analytics."
        return "Tests unsupported redirect methods; only GET and HEAD are allowed."
    return "Calls the specified application endpoint."


def main():
    purpose, *arguments = sys.argv[1:]
    method, url, body, user_agent = "GET", None, None, None
    headers = []
    timeout = None
    index = 0
    value_options = {"-H", "--header", "-X", "--request", "--data-raw", "--data", "--data-binary", "-d", "--user-agent", "-A", "--max-time", "--connect-timeout", "-D", "--dump-header", "-o", "--output", "-w", "--write-out"}
    while index < len(arguments):
        option = arguments[index]
        if option in value_options:
            index += 1
            value = arguments[index]
            if option in {"-H", "--header"}:
                name, separator, content = value.partition(":")
                headers.append(name + separator + content)
            elif option in {"-X", "--request"}:
                method = value
            elif option in {"--data-raw", "--data", "--data-binary", "-d"}:
                body = value
            elif option in {"--user-agent", "-A"}:
                user_agent = value
            elif option == "--max-time":
                timeout = value
        elif option in {"-I", "--head"}:
            method = "HEAD"
        elif option.startswith(("http://", "https://")):
            url = option
        index += 1
    if url is None:
        sys.exit("Cannot log request: curl URL is missing.")
    parsed = urlsplit(url)
    defaults = ["Host: " + parsed.netloc, "Accept: */*"]
    if user_agent:
        defaults.append("User-Agent: " + user_agent)
    if body is not None:
        defaults.append("Content-Length: " + str(len(body.encode("utf-8"))))
    configured_names = {line.partition(":")[0].lower() for line in headers}
    headers = [line for line in defaults if line.partition(":")[0].lower() not in configured_names] + headers
    stamp = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    print(f"\n[{stamp}] SENDING REQUEST")
    print("  Check: " + purpose)
    print("  API does: " + api_description(method, parsed.path))
    print("  Method: " + method)
    print("  URL: " + url)
    print("  Headers:")
    for line in headers:
        print("    " + line)
    print("  Body:")
    if body is None:
        print("    (none)")
    else:
        try:
            displayed = json.dumps(json.loads(body), indent=2, ensure_ascii=False)
        except ValueError:
            displayed = body
        for line in displayed.splitlines():
            print("    " + line)
    print("  Maximum request time: " + str(timeout or "curl default") + " seconds")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
