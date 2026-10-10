import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "skiko_winui_release.py"
SPEC = importlib.util.spec_from_file_location("skiko_winui_release", SCRIPT)
release_tool = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release_tool
SPEC.loader.exec_module(release_tool)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="skiko-winui-release-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git("init", "--initial-branch=release-fixture")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "release-test@example.invalid")
        (self.root / "skiko").mkdir()
        (self.root / "skiko/gradle.properties").write_text(
            "dependencies.skia=m154-upstream-fixture\n", encoding="utf-8")
        self.git("add", "skiko/gradle.properties")
        self.git("commit", "-m", "Upstream fixture")
        self.upstream = self.git("rev-parse", "HEAD")
        self.write_config()
        (self.root / "gradle.properties").write_text("kotlinWinRT.version=0.1.0\n", encoding="utf-8")
        (self.root / ".gitignore").write_text("skiko/build/\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-m", "Fork release configuration")
        self.release = release_tool.Release.load(self.root)
        self.git("tag", self.release.tag)
        github_environment = mock.patch.dict(os.environ, {"GITHUB_REF": ""})
        github_environment.start()
        self.addCleanup(github_environment.stop)
        self.repository = self.root / "skiko/build/repo"
        self.output = self.root / "skiko/build/release" / self.release.version

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.root), *arguments], check=True,
                              text=True, capture_output=True, encoding="utf-8").stdout.strip()

    def write_config(self, **overrides):
        properties = {"release.version": "0.154.0-alpha01", "upstream.version": "0.154.0",
                      "upstream.tag": "v0.154.0", "upstream.commit": self.upstream,
                      "kotlinWinRT.release.version": "0.1.0"}
        properties.update(overrides)
        (self.root / "skiko-winui-release.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8")

    def artifact_directory(self, artifact):
        return self.repository / Path(*release_tool.GROUP.split(".")) / artifact / self.release.version

    def make_publications(self, toolkit_version="0.1.0"):
        for artifact in release_tool.ARTIFACTS:
            directory = self.artifact_directory(artifact)
            directory.mkdir(parents=True)
            prefix = f"{artifact}-{self.release.version}"
            pom = ET.Element("project", xmlns=release_tool.XML_NS["m"])
            for key, value in {"groupId": release_tool.GROUP, "artifactId": artifact,
                               "version": self.release.version, "packaging": "jar"}.items():
                ET.SubElement(pom, key).text = value
            properties = ET.SubElement(pom, "properties")
            for key, value in {"skiko.upstream.version": self.release.upstream_version,
                               "skiko.upstream.tag": self.release.upstream_tag,
                               "skiko.upstream.commit": self.upstream}.items():
                ET.SubElement(properties, key).text = value
            ET.SubElement(ET.SubElement(pom, "scm"), "tag").text = self.release.tag
            if artifact in ("skiko-winui", "skiko-winui-jvm", "skiko-winui-mingw"):
                dependency = ET.SubElement(ET.SubElement(pom, "dependencies"), "dependency")
                for key, value in {"groupId": release_tool.GROUP, "artifactId": "winrt-runtime",
                                   "version": toolkit_version}.items():
                    ET.SubElement(dependency, key).text = value
            (directory / f"{prefix}.pom").write_bytes(ET.tostring(pom))
            for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
                (directory / f"{prefix}{suffix}").write_bytes(b"artifact fixture")
            component = {"group": release_tool.GROUP, "module": artifact,
                         "version": self.release.version, "attributes": {"org.gradle.status": "release"}}
            (directory / f"{prefix}.module").write_text(json.dumps({"component": component}), encoding="utf-8")
            for path in directory.iterdir():
                Path(f"{path}.asc").write_bytes(b"signature fixture")

    def bundle(self):
        return release_tool.bundle(self.root, self.release, self.repository, self.output)

    def test_metadata_uses_one_version_and_namespaced_tag(self):
        self.assertEqual(self.release.metadata("release")["version"], "0.154.0-alpha01")
        self.assertEqual(self.release.metadata("snapshot")["version"], "0.154.0-alpha01-SNAPSHOT")
        self.assertEqual(self.release.tag, "skiko-winui/v0.154.0-alpha01")

    def test_rejects_skia_from_a_newer_upstream(self):
        (self.root / "skiko/gradle.properties").write_text(
            "dependencies.skia=m155-incompatible\n", encoding="utf-8")
        with self.assertRaisesRegex(release_tool.ReleaseError, "requires Skia m154-upstream-fixture"):
            release_tool.verify(self.root, self.release)

    def test_rejects_inconsistent_release_mapping(self):
        for overrides in ({"release.version": "0.155.0-alpha01"},
                          {"release.version": "0.154.0-alpha1"},
                          {"upstream.tag": "v0.154.0-alpha01"},
                          {"upstream.commit": "shortsha"},
                          {"kotlinWinRT.release.version": "0.1.0-SNAPSHOT"}):
            with self.subTest(overrides=overrides):
                self.write_config(**overrides)
                with self.assertRaises(release_tool.ReleaseError):
                    release_tool.Release.load(self.root)

    def test_accepts_stable_release(self):
        self.write_config(**{"release.version": "0.154.0"})
        self.assertEqual(release_tool.Release.load(self.root).metadata("release")["prerelease"], "false")

    def test_verifies_merged_upstream_and_exact_release_tag(self):
        self.assertEqual(release_tool.verify(self.root, self.release, require_tag=True),
                         self.git("rev-parse", "HEAD"))

    def test_rejects_an_unmerged_commit(self):
        orphan = self.git("commit-tree", "HEAD^{tree}", "-m", "Unmerged upstream fixture")
        self.write_config(**{"upstream.commit": orphan})
        with self.assertRaisesRegex(release_tool.ReleaseError, "Merge pinned Skiko"):
            release_tool.verify(self.root, release_tool.Release.load(self.root))

    def test_rejects_upstream_tag_sha_mismatch(self):
        with mock.patch.object(release_tool, "git", side_effect=["", "0" * 40]):
            with self.assertRaisesRegex(release_tool.ReleaseError, "not the pinned"):
                release_tool.verify(self.root, self.release, fetch_upstream=True)

    def test_rejects_release_tag_on_another_commit(self):
        self.git("update-ref", f"refs/tags/{self.release.tag}", self.upstream)
        with self.assertRaisesRegex(release_tool.ReleaseError, "must point to HEAD"):
            release_tool.verify(self.root, self.release, require_tag=True)

    def test_rejects_dirty_source_before_upload(self):
        with (self.root / "skiko-winui-release.properties").open("a") as config:
            config.write("# Uncommitted source\n")
        with self.assertRaisesRegex(release_tool.ReleaseError, "Commit the release source"):
            release_tool.verify(self.root, self.release, require_tag=True)

    def test_rejects_publishing_from_a_branch_in_ci(self):
        with mock.patch.dict(os.environ, {"GITHUB_REF": "refs/heads/winui_dev"}):
            with self.assertRaisesRegex(release_tool.ReleaseError, "Publishing must run from"):
                release_tool.verify(self.root, self.release, require_tag=True)

    def test_bundle_contains_only_this_release_with_checksums(self):
        self.make_publications()
        stale = self.repository / "io/github/compose-fluent/skiko-winui-jvm/0.0.0-SNAPSHOT"
        stale.mkdir(parents=True)
        (stale / "stale.jar").write_bytes(b"old release")
        archive = self.bundle()
        with zipfile.ZipFile(archive) as zipped:
            names = zipped.namelist()
            self.assertTrue(names)
            self.assertFalse(any("SNAPSHOT" in name or "maven-metadata.xml" in name for name in names))
            for name in names:
                self.assertIn(f"/{self.release.version}/", name)
                if not name.endswith((".asc", *release_tool.HASH_SUFFIXES)):
                    self.assertIn(f"{name}.asc", names)
                    self.assertEqual(zipped.read(f"{name}.sha1").decode(),
                                     hashlib.sha1(zipped.read(name)).hexdigest())
        manifest = json.loads((self.output / "release-manifest.json").read_text())
        self.assertEqual(manifest["upstream"]["commit"], self.upstream)
        self.assertEqual(manifest["commit"], self.git("rev-parse", "HEAD"))
        self.assertEqual(manifest["backends"], ["winui-jvm", "winui-mingw"])
        self.assertEqual(archive.read_bytes(), self.bundle().read_bytes())

    def test_requires_both_backends_and_runtime_publications(self):
        self.make_publications()
        missing = self.artifact_directory("skiko-winui-mingw-runtime")
        missing.rename(missing.with_name("missing"))
        with self.assertRaisesRegex(release_tool.ReleaseError, "Missing publication"):
            self.bundle()

    def test_rejects_unsigned_files(self):
        self.make_publications()
        next(self.artifact_directory("skiko-winui-jvm").glob("*.pom.asc")).unlink()
        with self.assertRaisesRegex(release_tool.ReleaseError, "Missing PGP signature"):
            self.bundle()

    def test_rehearsal_allows_unsigned_snapshot_artifacts_but_cannot_upload(self):
        self.make_publications(toolkit_version="0.1.0-SNAPSHOT")
        (self.root / "gradle.properties").write_text("kotlinWinRT.version=0.1.0-SNAPSHOT\n")
        for artifact in release_tool.ARTIFACTS:
            for signature in self.artifact_directory(artifact).glob("*.asc"):
                signature.unlink()
        archive = release_tool.bundle(self.root, self.release, self.repository, self.output, dry_run=True)
        manifest = json.loads((self.output / "release-manifest.json").read_text())
        self.assertTrue(archive.name.endswith("-rehearsal.zip"))
        self.assertTrue(manifest["dryRun"])
        self.assertFalse(manifest["publishable"])
        self.assertTrue(manifest["unsignedArtifacts"])
        self.assertEqual(manifest["dependencies"]["kotlinWinRT"]["artifactVersions"]["winrt-runtime"],
                         "0.1.0-SNAPSHOT")
        with mock.patch.object(release_tool, "request") as request:
            with self.assertRaisesRegex(release_tool.ReleaseError, "rehearsal.*cannot be published"):
                release_tool.upload(self.root, self.release, self.output)
            request.assert_not_called()

    def test_formal_verification_waits_for_published_toolkit(self):
        (self.root / "gradle.properties").write_text("kotlinWinRT.version=0.1.0-SNAPSHOT\n")
        with self.assertRaisesRegex(release_tool.ReleaseError, "requires published kotlin-winrt 0.1.0"):
            release_tool.verify(self.root, self.release, for_publish=True)

    def test_signed_snapshot_dependencies_cannot_be_bundled_for_publication(self):
        self.make_publications(toolkit_version="0.1.0-SNAPSHOT")
        with self.assertRaisesRegex(release_tool.ReleaseError, "formal publication requires kotlin-winrt 0.1.0"):
            self.bundle()

    def test_gradle_metadata_cannot_mix_toolkit_versions(self):
        self.make_publications()
        path = next(self.artifact_directory("skiko-winui").glob("*.module"))
        metadata = json.loads(path.read_text())
        metadata["variants"] = [{"name": "metadataApiElements", "dependencies": [{
            "group": release_tool.GROUP, "module": "winrt-runtime", "version": {"requires": "0.1.0-SNAPSHOT"},
        }]}]
        path.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(release_tool.ReleaseError, "disagrees with POM"):
            self.bundle()

    def test_rejects_wrong_pom_provenance(self):
        self.make_publications()
        pom = next(self.artifact_directory("skiko-winui-jvm").glob("*.pom"))
        pom.write_text(pom.read_text().replace(self.upstream, "0" * 40))
        with self.assertRaisesRegex(release_tool.ReleaseError, "does not match pinned"):
            self.bundle()

    def test_accepts_kmp_target_metadata_pointing_to_root_component(self):
        self.make_publications()
        for artifact in ("skiko-winui-jvm", "skiko-winui-mingw"):
            path = next(self.artifact_directory(artifact).glob("*.module"))
            metadata = json.loads(path.read_text())
            metadata["component"].update({"module": "skiko-winui",
                "url": f"../../skiko-winui/{self.release.version}/skiko-winui-{self.release.version}.module"})
            path.write_text(json.dumps(metadata))
        self.bundle()

    def test_rejects_kmp_component_link_to_other_version(self):
        self.make_publications()
        path = next(self.artifact_directory("skiko-winui-jvm").glob("*.module"))
        metadata = json.loads(path.read_text())
        metadata["component"].update({"module": "skiko-winui",
            "url": "../../skiko-winui/0.0.0-SNAPSHOT/skiko-winui-0.0.0-SNAPSHOT.module"})
        path.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(release_tool.ReleaseError, "unexpected root component"):
            self.bundle()

    def test_requires_gradle_metadata_for_kmp_publications(self):
        self.make_publications()
        next(self.artifact_directory("skiko-winui-jvm").glob("*.module")).unlink()
        with self.assertRaisesRegex(release_tool.ReleaseError, "Missing Gradle module metadata"):
            self.bundle()

    def test_rejects_gradle_variant_redirect_to_other_version(self):
        self.make_publications()
        path = next(self.artifact_directory("skiko-winui").glob("*.module"))
        metadata = json.loads(path.read_text())
        metadata["variants"] = [{"name": "winuiJvmApiElements-published", "available-at": {
            "group": release_tool.GROUP, "module": "skiko-winui-jvm", "version": "0.0.0-SNAPSHOT",
        }}]
        path.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(release_tool.ReleaseError, "redirects outside this release"):
            self.bundle()

    def test_rejects_modified_bundle_before_contacting_central(self):
        self.make_publications()
        self.bundle().write_bytes(b"changed bundle")
        with mock.patch.object(release_tool, "request") as request:
            with self.assertRaisesRegex(release_tool.ReleaseError, "checksum"):
                release_tool.upload(self.root, self.release, self.output)
            request.assert_not_called()

    def test_upload_uses_portal_multipart_and_waits_for_publication(self):
        self.make_publications()
        self.bundle()
        deployment = "00000000-0000-0000-0000-000000000001"
        with mock.patch.dict(os.environ, {"MAVEN_CENTRAL_USERNAME": "fixture-user", "MAVEN_CENTRAL_PASSWORD": "fixture-password"}), \
                mock.patch.object(release_tool, "request", side_effect=[deployment,
                    json.dumps({"deploymentId": deployment, "deploymentState": "PUBLISHED"})]) as request:
            self.assertEqual(release_tool.upload(self.root, self.release, self.output), deployment)
        args = request.call_args_list[0].args
        self.assertIn("publishingType=AUTOMATIC", args[0])
        self.assertIn(b'name="bundle"', args[2])
        self.assertTrue(args[3].startswith("multipart/form-data; boundary="))

    def test_resume_does_not_upload_a_second_bundle(self):
        self.make_publications()
        self.bundle()
        deployment = "00000000-0000-0000-0000-000000000001"
        with mock.patch.dict(os.environ, {"MAVEN_CENTRAL_USERNAME": "fixture-user", "MAVEN_CENTRAL_PASSWORD": "fixture-password"}), \
                mock.patch.object(release_tool, "request", return_value=json.dumps({"deploymentState": "FAILED", "errors": {"fixture": ["bad signature"]}})) as request:
            with self.assertRaisesRegex(release_tool.ReleaseError, "validation failed"):
                release_tool.upload(self.root, self.release, self.output, deployment_id=deployment)
        self.assertEqual(request.call_count, 1)
        self.assertTrue(request.call_args.args[0].startswith("status?"))


if __name__ == "__main__":
    unittest.main()
