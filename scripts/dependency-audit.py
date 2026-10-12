"""Fail-closed OSV gate for the resolved Maven tree, including test dependencies."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess


def fetch(url, payload=None):
    command = ["curl", "-q", "--fail-with-body", "--silent", "--show-error", "--connect-timeout", "5", "--max-time", "30", "-H", "Content-Type: application/json"]
    if payload is not None:
        command += ["--data-binary", "@-"]
    response = subprocess.run(command + [url], input=None if payload is None else json.dumps(payload), text=True, capture_output=True, timeout=35)
    if response.returncode:
        raise RuntimeError("Advisory request failed: " + response.stderr.strip())
    return json.loads(response.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tree", type=Path, default=Path("target/dependency-tree.json"))
    parser.add_argument("--output", type=Path, default=Path("target/dependency-audit.json"))
    args = parser.parse_args()
    report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "source": "https://api.osv.dev/v1/querybatch",
              "status": "unavailable", "limitations": "Known indexed Maven advisories only; not a container/OS/build-plugin or source security audit."}
    try:
        root = json.loads(args.tree.read_text())
        packages = {}
        def walk(node):
            key = (node["groupId"] + ":" + node["artifactId"], node["version"])
            packages.setdefault(key, set()).add(node.get("scope", ""))
            for child in node.get("children", []):
                walk(child)
        for child in root.get("children", []):
            walk(child)
        if not packages:
            raise ValueError("Empty resolved dependency graph; no clean result claimed")
        keys = sorted(packages)
        results = []
        for offset in range(0, len(keys), 100):
            batch = keys[offset:offset + 100]
            response = fetch(report["source"], {"queries": [{"package": {"ecosystem": "Maven", "name": n}, "version": v} for n,v in batch]})
            if len(response["results"]) != len(batch):
                raise ValueError("Incomplete advisory response")
            for (name, version), result in zip(batch, response["results"]):
                if result.get("next_page_token"):
                    raise ValueError("Advisory pagination requires manual continuation; no clean result claimed")
                for finding in result.get("vulns", []):
                    results.append({"package": name, "version": version, "scopes": sorted(packages[(name,version)]), "id": finding["id"]})
        details = {}
        for identity in sorted({r["id"] for r in results}):
            raw = fetch("https://api.osv.dev/v1/vulns/" + identity)
            details[identity] = {k: raw.get(k) for k in ("id", "aliases", "summary", "severity", "database_specific", "modified", "affected", "references")}
        report.update(packageCount=len(keys), packages=[{"name": n,"version":v,"scopes":sorted(packages[(n,v)])} for n,v in keys],
                      findings=results, advisoryDetails=details, status="pass" if not results else "fail")
    except Exception as error:
        report["error"] = str(error)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print("OSV dependency gate:", report["status"], "packages:", report.get("packageCount", "unknown"), "findings:", len(report.get("findings", [])))
    for finding in report.get("findings", []):
        print(finding["package"], finding["version"], finding["id"])
    if report["status"] == "unavailable":
        print(report.get("error", "Unavailable dependency audit"))
    raise SystemExit(0 if report["status"] == "pass" else 2 if report["status"] == "unavailable" else 1)


if __name__ == "__main__":
    main()
