"""Install this clone's IntelliJ inspections and targeted cleanup-on-save settings.

Close this project in IntelliJ first so the IDE cannot overwrite workspace.xml.
Existing settings are merged, and originals are backed up in .formatting/build.
"""

import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET


def component(project, name):
    existing = project.find(f"component[@name='{name}']")
    return existing if existing is not None else ET.SubElement(project, "component", name=name)


def option(parent, name, value):
    existing = parent.find(f"option[@name='{name}']")
    if existing is None:
        existing = ET.SubElement(parent, "option", name=name)
    existing.set("value", value)


def project_file(path):
    return ET.parse(path).getroot() if path.exists() else ET.Element("project", version="4")


def xml_bytes(project):
    ET.indent(project, space="  ")
    return ET.tostring(project, encoding="utf-8", xml_declaration=True) + b"\n"


def install(root):
    idea = root / ".idea"
    profiles = idea / "inspectionProfiles"
    changes = {
        profiles / name: (root / ".formatting/intellij" / name).read_bytes()
        for name in ("GG2_Java.xml", "GG2_Cleanup.xml")
    }
    settings = project_file(profiles / "profiles_settings.xml")
    manager = component(settings, "InspectionProjectProfileManager")
    option(manager, "PROJECT_PROFILE", "GG2 Java")
    option(manager, "USE_PROJECT_PROFILE", "true")
    changes[profiles / "profiles_settings.xml"] = xml_bytes(settings)

    misc = project_file(idea / "misc.xml")
    nullability = component(misc, "NullableNotNullManager")
    option(nullability, "myDefaultNullable", "org.jetbrains.annotations.Nullable")
    option(nullability, "myDefaultNotNull", "org.jetbrains.annotations.NotNull")
    # Older IDEA versions use these defaults; newer versions use ordered lists.
    option(nullability, "myOrdered", "true")
    for name, annotation in (
        ("myNullables", "org.jetbrains.annotations.Nullable"),
        ("myNotNulls", "org.jetbrains.annotations.NotNull"),
    ):
        holder = nullability.find(f"option[@name='{name}']")
        values = [] if holder is None else [item.attrib["itemvalue"] for item in holder.findall("value/list/item")]
        if holder is not None:
            nullability.remove(holder)
        holder = ET.SubElement(nullability, "option", name=name)
        entries = ET.SubElement(ET.SubElement(holder, "value"), "list", size=str(len(set(values + [annotation]))))
        for index, value in enumerate(dict.fromkeys([annotation, *values])):
            ET.SubElement(entries, "item", index=str(index), **{"class": "java.lang.String", "itemvalue": value})
    changes[idea / "misc.xml"] = xml_bytes(misc)

    workspace = project_file(idea / "workspace.xml")
    cleanup = component(workspace, "CodeCleanupOnSaveOptions")
    option(cleanup, "PROFILE", "GG2 Cleanup")
    properties = component(workspace, "PropertiesComponent")
    if properties.find("property") is not None:
        flag = properties.find("property[@name='code.cleanup.on.save']")
        if flag is None:
            flag = ET.SubElement(properties, "property", name="code.cleanup.on.save")
        flag.set("value", "true")
    else:
        data = json.loads(properties.text) if properties.text and properties.text.strip() else {}
        data.setdefault("keyToString", {})["code.cleanup.on.save"] = "true"
        properties.text = json.dumps(data, indent=2, ensure_ascii=False)
    changes[idea / "workspace.xml"] = xml_bytes(workspace)

    # Prepare everything before writing: malformed existing XML/JSON leaves the
    # clone untouched. Back up existing files without including them in Git.
    backup_root = root / ".formatting/build"
    backup_root.mkdir(parents=True, exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix="idea-backup-", dir=backup_root))
    for path, content in changes.items():
        if path.exists() and path.read_bytes() == content:
            continue
        if path.exists():
            original = backup / path.relative_to(idea)
            original.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, original)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return backup


if __name__ == "__main__":
    backup = install(Path(__file__).resolve().parent.parent)
    print(f"IntelliJ settings installed. Previous files: {backup}")
