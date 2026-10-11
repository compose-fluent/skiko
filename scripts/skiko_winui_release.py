#!/usr/bin/env python3
"""Pin Skiko provenance, assemble signed Maven artifacts, and use Central Portal.

Only the explicit `upload` command contacts the publishing API. See
docs/skiko-winui-releasing.md for the release and snapshot workflows.
"""

import argparse
import base64
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile


UPSTREAM = "https://github.com/JetBrains/skiko"
CENTRAL = "https://central.sonatype.com/api/v1/publisher"
GROUP = "io.github.compose-fluent"
ARTIFACTS = (
    "skiko-winui", "skiko-winui-jvm", "skiko-winui-windows",
    "skiko-winui-mingw", "skiko-winui-mingw-runtime",
)
HASH_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
XML_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class ReleaseError(Exception):
    pass


@dataclass(frozen=True)
class Release:
    version: str
    upstream_version: str
    upstream_tag: str
    upstream_commit: str
    kotlin_winrt_version: str

    @property
    def tag(self):
        return f"skiko-winui/v{self.version}"

    @classmethod
    def load(cls, root):
        path = root / "skiko-winui-release.properties"
        properties = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            key, separator, value = line.partition("=")
            if not separator or key.strip() in properties:
                raise ReleaseError(f"Invalid or duplicate property in {path}: {key}")
            properties[key.strip()] = value.strip()
        keys = ("release.version", "upstream.version", "upstream.tag", "upstream.commit",
                "kotlinWinRT.release.version")
        if any(not properties.get(key) for key in keys):
            raise ReleaseError(f"Missing release/upstream identity in {path}")
        release = cls(*(properties[key] for key in keys))
        version_pattern = re.escape(release.upstream_version) + r"(?:-(?:alpha|beta|rc)[0-9]+)?"
        if (not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", release.upstream_version)
                or not re.fullmatch(version_pattern, release.version)
                or release.upstream_tag != f"v{release.upstream_version}"
                or not re.fullmatch(r"[0-9a-f]{40}", release.upstream_commit)
                or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", release.kotlin_winrt_version)):
            raise ReleaseError(f"Invalid version/tag/commit mapping in {path}")
        return release

    def metadata(self, channel):
        return {
            "version": self.version if channel == "release" else f"{self.version}-SNAPSHOT",
            "release_tag": self.tag,
            "upstream_version": self.upstream_version,
            "upstream_tag": self.upstream_tag,
            "upstream_commit": self.upstream_commit,
            "kotlin_winrt_release_version": self.kotlin_winrt_version,
            "prerelease": "true" if "-" in self.version else "false",
        }


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], text=True,
                            capture_output=True, encoding="utf-8")
    if result.returncode:
        raise ReleaseError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def verify_release_dependency(root, release):
    properties = dict(line.split("=", 1) for line in
                      (root / "gradle.properties").read_text(encoding="utf-8").splitlines()
                      if "=" in line and not line.lstrip().startswith(("#", "!")))
    actual = properties.get("kotlinWinRT.version", "").strip()
    if actual != release.kotlin_winrt_version:
        raise ReleaseError(
            f"Formal publication requires published kotlin-winrt {release.kotlin_winrt_version}; "
            f"kotlinWinRT.version is {actual or 'unset'}. Use bundle --dry-run for a rehearsal.")


def verify(root, release, fetch_upstream=False, require_tag=False, for_publish=False):
    if for_publish:
        verify_release_dependency(root, release)
        require_tag = True
    upstream_ref = f"refs/skiko-winui/upstream/{release.upstream_tag}"
    if fetch_upstream:
        git(root, "fetch", "--no-tags", f"{UPSTREAM}.git",
            f"refs/tags/{release.upstream_tag}:{upstream_ref}")
        actual = git(root, "rev-parse", f"{upstream_ref}^{{commit}}")
        if actual != release.upstream_commit:
            raise ReleaseError(f"{release.upstream_tag} resolves to {actual}, not the pinned {release.upstream_commit}")
    try:
        git(root, "merge-base", "--is-ancestor", release.upstream_commit, "HEAD")
    except ReleaseError as error:
        raise ReleaseError(f"Merge pinned Skiko {release.upstream_tag} ({release.upstream_commit}) before releasing") from error
    upstream_properties = git(root, "show", f"{release.upstream_commit}:skiko/gradle.properties")
    current_properties = (root / "skiko/gradle.properties").read_text(encoding="utf-8")
    def skia_version(properties):
        match = re.search(r"^dependencies\.skia\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
        if not match:
            raise ReleaseError("Missing dependencies.skia in Skiko build properties")
        return match.group(1)
    expected_skia = skia_version(upstream_properties)
    actual_skia = skia_version(current_properties)
    if actual_skia != expected_skia:
        raise ReleaseError(
            f"Pinned Skiko {release.upstream_tag} requires Skia {expected_skia}; "
            f"dependencies.skia is {actual_skia}")
    commit = git(root, "rev-parse", "HEAD")
    if require_tag:
        if git(root, "rev-parse", f"refs/tags/{release.tag}^{{commit}}") != commit:
            raise ReleaseError(f"Release tag {release.tag} must point to HEAD")
        if git(root, "status", "--porcelain"):
            raise ReleaseError("Commit the release source and configuration before publishing")
        github_ref = os.environ.get("GITHUB_REF")
        if github_ref and github_ref != f"refs/tags/{release.tag}":
            raise ReleaseError(f"Publishing must run from refs/tags/{release.tag}, not {github_ref}")
    return commit


def validate_pom(data, release, artifact):
    try:
        pom = ET.fromstring(data)
    except ET.ParseError as error:
        raise ReleaseError(f"Invalid POM for {artifact}") from error
    expected = {"groupId": GROUP, "artifactId": artifact, "version": release.version}
    for key, value in expected.items():
        if pom.findtext(f"m:{key}", namespaces=XML_NS) != value:
            raise ReleaseError(f"POM {artifact} has an unexpected {key}; expected {value}")
    for key, value in {
        "skiko.upstream.version": release.upstream_version,
        "skiko.upstream.tag": release.upstream_tag,
        "skiko.upstream.commit": release.upstream_commit,
    }.items():
        if pom.findtext(f"m:properties/m:{key}", namespaces=XML_NS) != value:
            raise ReleaseError(f"POM {artifact} does not match pinned {key}")
    if pom.findtext("m:scm/m:tag", namespaces=XML_NS) != release.tag:
        raise ReleaseError(f"POM {artifact} does not identify release tag {release.tag}")
    toolkit_dependencies = {}
    for dependency in pom.findall("m:dependencies/m:dependency", XML_NS):
        group = dependency.findtext("m:groupId", namespaces=XML_NS)
        module = dependency.findtext("m:artifactId", namespaces=XML_NS)
        version = dependency.findtext("m:version", namespaces=XML_NS)
        if group == GROUP and module in ARTIFACTS and version != release.version:
            raise ReleaseError(f"POM {artifact} mixes skiko-winui release versions")
        if group == GROUP and module and module.startswith("winrt-"):
            toolkit_dependencies[module] = version
    if artifact in ("skiko-winui", "skiko-winui-jvm", "skiko-winui-mingw") and not toolkit_dependencies:
        raise ReleaseError(f"POM {artifact} is missing kotlin-winrt dependencies")
    return toolkit_dependencies


def bundle(root, release, repository, output, dry_run=False):
    commit = verify(root, release)
    dirty = bool(git(root, "status", "--porcelain"))
    if not dry_run:
        verify_release_dependency(root, release)
        if dirty:
            raise ReleaseError("Commit the release source and configuration before assembling a publishable bundle")
    files = {}
    toolkit_dependencies = {}
    unsigned = []
    group_path = Path(*GROUP.split("."))
    for artifact in ARTIFACTS:
        directory = repository / group_path / artifact / release.version
        if not directory.is_dir():
            raise ReleaseError(f"Missing publication: {directory}")
        prefix = f"{artifact}-{release.version}"
        pom_path = directory / f"{prefix}.pom"
        if not pom_path.is_file():
            raise ReleaseError(f"Missing POM: {pom_path}")
        if artifact in ("skiko-winui", "skiko-winui-jvm", "skiko-winui-mingw") and not (directory / f"{prefix}.module").is_file():
            raise ReleaseError(f"Missing Gradle module metadata for {artifact}")
        dependencies = validate_pom(pom_path.read_bytes(), release, artifact)
        for module, version in dependencies.items():
            if module in toolkit_dependencies and toolkit_dependencies[module] != version:
                raise ReleaseError(f"POMs mix kotlin-winrt versions for {module}")
            if not dry_run and version != release.kotlin_winrt_version:
                raise ReleaseError(f"POM {artifact} requires {module}:{version}; formal publication requires kotlin-winrt {release.kotlin_winrt_version}")
            toolkit_dependencies[module] = version
        payloads = sorted(path for path in directory.iterdir()
                          if path.is_file() and path.name.startswith((prefix + ".", prefix + "-")))
        payloads = [path for path in payloads
                    if not path.name.endswith((".asc", *HASH_SUFFIXES))]
        packaging = ET.fromstring(pom_path.read_bytes()).findtext("m:packaging", "jar", XML_NS)
        if packaging != "pom":
            if not (directory / f"{prefix}.{packaging}").is_file():
                raise ReleaseError(f"Missing {packaging} artifact for {artifact}")
            for classifier in ("sources", "javadoc"):
                if not (directory / f"{prefix}-{classifier}.jar").is_file():
                    raise ReleaseError(f"Missing {classifier} jar for {artifact}")
        for path in payloads:
            signature = Path(f"{path}.asc")
            signed = signature.is_file() and signature.stat().st_size > 0
            if not signed and not dry_run:
                raise ReleaseError(f"Missing PGP signature for {path.name}")
            relative = path.relative_to(repository).as_posix()
            data = path.read_bytes()
            if path.suffix == ".module":
                metadata = json.loads(data)
                component = metadata["component"]
                component_module = artifact
                if "url" in component:
                    # KMP target publications point back to the root component.
                    component_module = "skiko-winui"
                    expected_url = f"../../skiko-winui/{release.version}/skiko-winui-{release.version}.module"
                    if artifact not in ("skiko-winui-jvm", "skiko-winui-mingw") or component["url"] != expected_url:
                        raise ReleaseError(f"Gradle metadata {artifact} points to an unexpected root component")
                if any(component.get(key) != value for key, value in
                       {"group": GROUP, "module": component_module, "version": release.version}.items()):
                    raise ReleaseError(f"Gradle metadata {artifact} has unexpected coordinates")
                for variant in metadata.get("variants", []):
                    redirect = variant.get("available-at", {})
                    if redirect.get("group") == GROUP and (
                            redirect.get("module") not in ARTIFACTS
                            or redirect.get("version") != release.version):
                        raise ReleaseError(f"Gradle variant {variant.get('name')} redirects outside this release")
                    if redirect.get("group") == GROUP:
                        module = redirect["module"]
                        expected_url = f"../../{module}/{release.version}/{module}-{release.version}.module"
                        if redirect.get("url") != expected_url:
                            raise ReleaseError(f"Gradle variant {variant.get('name')} has an unexpected redirect URL")
                    for dependency in variant.get("dependencies", []):
                        if (dependency.get("group") == GROUP and dependency.get("module") in ARTIFACTS
                                and dependency.get("version", {}).get("requires") != release.version):
                            raise ReleaseError(f"Gradle variant {variant.get('name')} mixes skiko-winui release versions")
                        if dependency.get("group") == GROUP and dependency.get("module", "").startswith("winrt-"):
                            module = dependency["module"]
                            version = dependency.get("version", {}).get("requires")
                            if module in toolkit_dependencies and toolkit_dependencies[module] != version:
                                raise ReleaseError(f"Gradle metadata disagrees with POM kotlin-winrt version for {module}")
                            if not dry_run and version != release.kotlin_winrt_version:
                                raise ReleaseError(f"Gradle metadata requires {module}:{version}; formal publication requires kotlin-winrt {release.kotlin_winrt_version}")
                            toolkit_dependencies[module] = version
            files[relative] = data
            if signed:
                files[f"{relative}.asc"] = signature.read_bytes()
            else:
                unsigned.append(relative)
            for algorithm in ("md5", "sha1"):
                files[f"{relative}.{algorithm}"] = hashlib.new(algorithm, data).hexdigest().encode("ascii")
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"skiko-winui-{release.version}-{'rehearsal' if dry_run else 'central'}.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zipped:
        for name, data in sorted(files.items()):
            # Fixed timestamps make assembling the same artifact set deterministic.
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            zipped.writestr(info, data)
    manifest = {
        "schemaVersion": 2,
        "version": release.version,
        "tag": release.tag,
        "commit": commit,
        "dryRun": dry_run,
        "publishable": not dry_run,
        "sourceDirty": dirty,
        "dependencies": {"kotlinWinRT": {"requiredVersion": release.kotlin_winrt_version,
                                         "artifactVersions": dict(sorted(toolkit_dependencies.items()))}},
        "unsignedArtifacts": unsigned,
        "upstream": {"repository": UPSTREAM, "version": release.upstream_version,
                     "tag": release.upstream_tag, "commit": release.upstream_commit},
        "backends": ["winui-jvm", "winui-mingw"],
        "bundle": {"file": archive.name, "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()},
        "artifacts": [{"path": name, "sha256": hashlib.sha256(data).hexdigest()}
                      for name, data in sorted(files.items()) if not name.endswith((".asc", *HASH_SUFFIXES))],
    }
    (output / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    notes_source = root / "docs/releases" / f"skiko-winui-{release.version}.md"
    notes = (
        f"# skiko-winui {release.version}\n\n"
        f"Based on [Skiko {release.upstream_version}]({UPSTREAM}/tree/{release.upstream_tag}).\n\n"
        f"- Upstream commit: [{release.upstream_commit}]({UPSTREAM}/commit/{release.upstream_commit})\n"
        f"- Release commit: `{commit}`\n"
        f"- Backends packaged: `winui-jvm`, `winui-mingw` (`mingwX64`).\n"
        f"- Maven group: `{GROUP}`. All five artifacts use `{release.version}`.\n\n"
        "The attached manifest records the source mapping and artifact checksums.\n"
    )
    if notes_source.is_file():
        notes = notes_source.read_text(encoding="utf-8")
    if dry_run:
        notes = "**Rehearsal only; not publishable.**\n\n" + notes
    (output / "release-notes.md").write_text(notes, encoding="utf-8")
    return archive


def request(path, token, data=b"", content_type=None):
    headers = {"Authorization": f"Bearer {token}"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(f"{CENTRAL}/{path}", data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        raise ReleaseError(f"Central Portal {path.split('?')[0]} returned HTTP {error.code}") from error


def upload(root, release, output, deployment_id=None, timeout=1800):
    manifest = json.loads((output / "release-manifest.json").read_text(encoding="utf-8"))
    if (manifest.get("schemaVersion") != 2 or manifest.get("dryRun") is not False
            or manifest.get("publishable") is not True or manifest.get("sourceDirty") is not False
            or manifest.get("unsignedArtifacts") != []):
        raise ReleaseError("A rehearsal, unsigned, or dirty-source bundle cannot be published; rebuild a signed release bundle")
    commit = verify(root, release, for_publish=True)
    expected_upstream = {"repository": UPSTREAM, "version": release.upstream_version,
                         "tag": release.upstream_tag, "commit": release.upstream_commit}
    if (manifest["version"] != release.version or manifest["tag"] != release.tag
            or manifest["commit"] != commit or manifest["upstream"] != expected_upstream):
        raise ReleaseError("The bundle manifest does not match this checkout and release configuration")
    archive = output / f"skiko-winui-{release.version}-central.zip"
    data = archive.read_bytes()
    if (manifest["bundle"]["file"] != archive.name
            or manifest["bundle"]["sha256"] != hashlib.sha256(data).hexdigest()):
        raise ReleaseError("Bundle checksum does not match the release manifest")
    with zipfile.ZipFile(archive) as zipped:
        for artifact in ARTIFACTS:
            path = f"{GROUP.replace('.', '/')}/{artifact}/{release.version}/{artifact}-{release.version}.pom"
            for module, version in validate_pom(zipped.read(path), release, artifact).items():
                if version != release.kotlin_winrt_version:
                    raise ReleaseError(f"Bundle requires {module}:{version}; formal publication requires kotlin-winrt {release.kotlin_winrt_version}")
    username = os.environ.get("MAVEN_CENTRAL_USERNAME")
    password = os.environ.get("MAVEN_CENTRAL_PASSWORD")
    if not username or not password:
        raise ReleaseError("MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must contain Central Portal user-token credentials")
    token = base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
    if not deployment_id:
        boundary = f"skiko-winui-{uuid.uuid4().hex}"
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; "
                f"filename=\"{archive.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
        body += data + f"\r\n--{boundary}--\r\n".encode()
        query = urllib.parse.urlencode({"name": f"skiko-winui {release.version}", "publishingType": "AUTOMATIC"})
        deployment_id = request(f"upload?{query}", token, body, f"multipart/form-data; boundary={boundary}").strip()
    if not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", deployment_id):
        raise ReleaseError("Central Portal did not return a valid deployment ID")
    record = output / "central-deployment.json"
    record.write_text(json.dumps({"deploymentId": deployment_id, "deploymentState": "UPLOADED"}, indent=2) + "\n",
                      encoding="utf-8")
    deadline = time.monotonic() + timeout
    last_state = None
    while time.monotonic() < deadline:
        status = json.loads(request(f"status?{urllib.parse.urlencode({'id': deployment_id})}", token))
        record.write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
        state = status.get("deploymentState")
        if state != last_state:
            print(f"Central deployment {deployment_id}: {state}", flush=True)
            last_state = state
        if state == "PUBLISHED":
            return deployment_id
        if state == "FAILED":
            raise ReleaseError(f"Central validation failed: {json.dumps(status.get('errors', {}))}")
        time.sleep(30)
    raise ReleaseError(f"Central deployment {deployment_id} is still pending. Resume with upload --deployment-id {deployment_id}; do not upload it again.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    subparsers = parser.add_subparsers(dest="command", required=True)
    metadata_parser = subparsers.add_parser("metadata")
    metadata_parser.add_argument("--channel", choices=("snapshot", "release"), default="snapshot")
    metadata_parser.add_argument("--github-output", type=Path)
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--fetch-upstream", action="store_true")
    verify_parser.add_argument("--require-tag", action="store_true")
    verify_parser.add_argument("--for-publish", action="store_true", help="Also require the published kotlin-winrt version and an exact release tag")
    bundle_parser = subparsers.add_parser("bundle")
    bundle_parser.add_argument("--repository", type=Path)
    bundle_parser.add_argument("--output", type=Path)
    bundle_parser.add_argument("--dry-run", action="store_true", help="Prepare a nonpublishable rehearsal bundle; allow unsigned and SNAPSHOT dependencies")
    upload_parser = subparsers.add_parser("upload")
    upload_parser.add_argument("--output", type=Path)
    upload_parser.add_argument("--deployment-id")
    upload_parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    root = args.root.resolve()
    release = Release.load(root)
    if args.command == "metadata":
        metadata = release.metadata(args.channel)
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write("".join(f"{key}={value}\n" for key, value in metadata.items()))
        print(json.dumps(metadata, indent=2))
    elif args.command == "verify":
        commit = verify(root, release, args.fetch_upstream, args.require_tag, args.for_publish)
        print(f"Verified Skiko {release.upstream_tag} ({release.upstream_commit}) in {commit}")
    elif args.command == "bundle":
        print(bundle(root, release, args.repository or root / "skiko/build/repo",
                     args.output or root / f"skiko/build/{'rehearsal' if args.dry_run else 'release'}/{release.version}",
                     args.dry_run))
    elif args.command == "upload":
        upload(root, release, args.output or root / f"skiko/build/release/{release.version}",
               args.deployment_id, args.timeout)


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, OSError, ValueError, KeyError) as error:
        print(f"Release error: {error}", file=sys.stderr)
        sys.exit(1)
