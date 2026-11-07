import json
import subprocess
import sys
from pathlib import Path

CATALOG = Path(__file__).parent / "catalog.json"


def load_catalog():
    with open(CATALOG, encoding="utf-8") as f:
        return json.load(f)["tools"]


def install(tool_id):
    tools = {t["id"]: t for t in load_catalog()}
    if tool_id not in tools:
        return False, f"ابزار '{tool_id}' در کاتالوگ نیست."
    tool = tools[tool_id]
    if tool["type"] == "pip":
        cmd = [sys.executable, "-m", "pip", "install", tool["package"]]
        result = subprocess.run(cmd, capture_output=True, text=True)
        ok = result.returncode == 0
        return ok, result.stdout if ok else result.stderr
    return False, f"نوع نصب '{tool['type']}' پشتیبانی نمی‌شود."


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] == "list":
        for t in load_catalog():
            print(f"{t['id']:12} {t['name']} - {t['description']}")
    else:
        ok, msg = install(sys.argv[1])
        print(msg)
        sys.exit(0 if ok else 1)
                   else:
        _usage()
        sys.exit(2)       
