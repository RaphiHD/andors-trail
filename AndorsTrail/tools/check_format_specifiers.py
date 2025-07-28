import re
import os
import argparse
from xml.etree import ElementTree as ET

def get_string_value_and_specifiers(filepath, key_name):
    """
    Parses an XML strings file to find a specific key and its format specifiers.

    Args:
        filepath (str): Path to the strings.xml file.
        key_name (str): The name of the string resource to find.

    Returns:
        tuple: (string_value, set_of_specifiers) or (None, None) if key not found.
               Specifiers are returned as a set, e.g., {"%1$s", "%2$d"}.
    """
    try:
        tree = ET.parse(filepath)
        root = tree.getroot()
        for string_tag in root.findall('string'):
            if string_tag.get('name') == key_name:
                value = string_tag.text if string_tag.text else ""
                # Regex to find format specifiers like %s, %d, %1$s, %2$d, etc.
                # It handles optional positional arguments (e.g., 1$) and type characters.
                specifiers = set(re.findall(r'%(?:(?:\d+\$)?(?:[sdfeoxXgGaAbhHc]))', value))
                return value, specifiers
    except ET.ParseError:
        print(f"Warning: Could not parse XML file: {filepath}")
    except FileNotFoundError:
        print(f"Warning: File not found: {filepath}")
    return None, None

def find_res_directories(project_root):
    """
    Finds all 'res' directories within a project, typically in module roots.
    """
    res_dirs = []
    for root_dir, dirs, _ in os.walk(project_root):
        if 'res' in dirs:
            res_dirs.append(os.path.join(root_dir, 'res'))
    if not res_dirs and os.path.basename(project_root) == 'res': # If project_root itself is a res dir
        res_dirs.append(project_root)
    return res_dirs


def find_strings_files(res_dir_path, base_filename="strings.xml"):
    """
    Finds all strings.xml files (or variants like strings-es.xml)
    within a given 'res' directory.
    """
    strings_files = {} # lang_code -> filepath
    for dirpath, _, filenames in os.walk(res_dir_path):
        if "values" in os.path.basename(dirpath).lower(): # e.g., values, values-es, values-en-rGB
            for filename in filenames:
                if filename.startswith(os.path.splitext(base_filename)[0]) and filename.endswith(".xml"):
                    full_path = os.path.join(dirpath, filename)
                    # Determine language code from directory name (e.g., "values-es" -> "es")
                    # or default if it's just "values"
                    dir_name_parts = os.path.basename(dirpath).split('-')
                    lang_code = "default" # For the base "values" folder
                    if len(dir_name_parts) > 1:
                        lang_code = "-".join(dir_name_parts[1:]) # Handles values-en-rUS correctly
                    strings_files[lang_code] = full_path
    return strings_files


def main():
    parser = argparse.ArgumentParser(
        description="Check format specifier consistency across language files for a given string key."
    )
    parser.add_argument(
        "project_root",
        help="Path to the Android project's root directory (or a specific module's root, or a 'res' directory)."
    )
    parser.add_argument(
        "key_name",
        help="The name of the string resource to check (e.g., 'skill_longdescription_evasion')."
    )
    parser.add_argument(
        "--base_lang",
        default="default",
        help="The language code for the base/reference strings file (e.g., 'en', 'default' for values/strings.xml). Default is 'default'."
    )
    parser.add_argument(
        "--strings_filename",
        default="strings.xml",
        help="The base name of your strings files (default: strings.xml)."
    )

    args = parser.parse_args()

    print(f"Searching for string key: '{args.key_name}'")
    print(f"Using base language: '{args.base_lang}' from file '{args.strings_filename}'")
    print(f"Project root/res path: {args.project_root}\n")

    res_directories = find_res_directories(args.project_root)
    if not res_directories:
        print(f"Error: No 'res' directory found under {args.project_root}")
        return

    all_strings_files = {}
    for res_dir in res_directories:
        # print(f"Scanning res directory: {res_dir}")
        all_strings_files.update(find_strings_files(res_dir, args.strings_filename))

    if not all_strings_files:
        print(f"Error: No '{args.strings_filename}' files found in any 'res/values-*' directories under {args.project_root}.")
        return

    base_file_path = all_strings_files.get(args.base_lang)

    if not base_file_path:
        # Try to find a default if 'en' or other specific base_lang isn't explicitly there
        # but a 'values/strings.xml' (mapped to 'default') exists.
        if args.base_lang != "default" and all_strings_files.get("default"):
            print(f"Warning: Base language '{args.base_lang}' not found. Using 'default' (values/{args.strings_filename}) as base.")
            base_file_path = all_strings_files.get("default")
            args.base_lang = "default" # Update for consistency in messages
        else:
            print(f"Error: Base strings file for language '{args.base_lang}' (expected at e.g., values-{args.base_lang}/{args.strings_filename} or values/{args.strings_filename}) not found.")
            print(f"Available language files found: {list(all_strings_files.keys())}")
            return

    base_value, base_specifiers = get_string_value_and_specifiers(base_file_path, args.key_name)

    if base_specifiers is None:
        print(f"Error: Key '{args.key_name}' not found in the base language file: {base_file_path}")
        return

    print(f"--- Base ({args.base_lang}) ---")
    print(f"File: {base_file_path}")
    print(f"Value: \"{base_value}\"")
    print(f"Specifiers: {sorted(list(base_specifiers)) if base_specifiers else 'None'}\n")

    issues_found = 0

    for lang_code, file_path in all_strings_files.items():
        if lang_code == args.base_lang:
            continue # Skip the base language itself

        current_value, current_specifiers = get_string_value_and_specifiers(file_path, args.key_name)

        if current_specifiers is None:
            # Only warn if the key is expected to be translated but is missing
            # (Some keys might not be translated by design, but for specifier check, we assume it should exist if base does)
#             print(f"--- Language: {lang_code} ---")
#             print(f"File: {file_path}")
#             print(f"Warning: Key '{args.key_name}' not found in this language file.")
#             print("-" * 20)
            continue

        if current_specifiers != base_specifiers:
            issues_found += 1
            print(f"--- Language: {lang_code} (ISSUE FOUND) ---")
            print(f"File: {file_path}")
            print(f"Value: \"{current_value}\"")
            print(f"Specifiers: {sorted(list(current_specifiers)) if current_specifiers else 'None'}")
            print(f"Expected specifiers (from base): {sorted(list(base_specifiers)) if base_specifiers else 'None'}")

            missing_in_current = base_specifiers - current_specifiers
            extra_in_current = current_specifiers - base_specifiers

            if missing_in_current:
                print(f"  MISSING in '{lang_code}': {sorted(list(missing_in_current))}")
            if extra_in_current:
                print(f"  EXTRA in '{lang_code}': {sorted(list(extra_in_current))}")
            print("-" * 20)
        # Optional: Print info for consistent files too
        # else:
        #     print(f"--- Language: {lang_code} (OK) ---")
        #     print(f"File: {file_path}")
        #     print(f"Specifiers: {current_specifiers}")
        #     print("-" * 20)


    if issues_found == 0:
        print(f"\nNo specifier mismatches found for key '{args.key_name}' compared to the base language.")
    else:
        print(f"\nFound {issues_found} potential specifier mismatch(es) for key '{args.key_name}'.")

if __name__ == "__main__":
    main()
