import hashlib
import json
import os

version = os.environ["VERSION"]
mc_version = os.environ["MC_VERSION"]
platforms = [p.strip() for p in os.environ["PLATFORMS"].split(",")]
repo = os.environ["GITHUB_REPOSITORY"]
tag = os.environ["RELEASE_TAG"]

entries = []
existing_path = "companion-versions.json"
if os.path.exists(existing_path):
    with open(existing_path) as f:
        existing = json.load(f)
    entries = [e for e in existing["entries"] if e["mcVersion"] != mc_version]

jars = {}
for loader in platforms:
    jar_path = f"{loader}/build/libs/tritiumcompanion-{loader}-{version}.jar"
    with open(jar_path, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest()
    url = f"https://github.com/{repo}/releases/download/{tag}/tritiumcompanion-{loader}-{version}.jar"
    jars[loader] = {
        "url": url,
        "sha256": sha256,
        "fileName": f"tritiumcompanion-{loader}-{version}.jar",
    }

entries.append(
    {
        "mcVersion": mc_version,
        "loaders": platforms,
        "modVersion": version,
        "jars": jars,
    }
)

os.makedirs("manifest-build", exist_ok=True)
with open("manifest-build/companion-versions.json", "w") as f:
    json.dump({"schema": 1, "entries": entries}, f, indent=2)
