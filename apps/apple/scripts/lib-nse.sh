# Sourced by test-ios.sh / test-macos.sh after a test build.

# Usage: built_products_dir <scheme> <destination>
built_products_dir() {
  local settings dir
  # Capture first: piping xcodebuild into an early-exiting reader can SIGPIPE under pipefail.
  settings=$(xcodebuild -project App.xcodeproj -scheme "$1" -configuration Debug -destination "$2" -showBuildSettings 2>/dev/null) || true
  dir=$(printf '%s\n' "$settings" | awk -F' = ' '/^ *BUILT_PRODUCTS_DIR = /&&!d{d=$2} END{print d}')
  [ -n "$dir" ] || { echo "BUILT_PRODUCTS_DIR not found" >&2; return 1; }
  printf '%s\n' "$dir"
}

# Usage: check_nse <scheme> <destination> <appex Info.plist glob inside App.app>
check_nse() {
  local dir plist got
  dir=$(built_products_dir "$1" "$2") || return 1
  plist=$(ls "$dir"/App.app/$3 2>/dev/null | head -1) || true
  [ -n "$plist" ] || { echo "NSE Info.plist not found under $dir/App.app/$3" >&2; return 1; }
  got=$(plutil -extract NSExtension.NSExtensionPointIdentifier raw "$plist")
  [ "$got" = "com.apple.usernotifications.service" ] || { echo "NSE extension point is '$got'" >&2; return 1; }
  echo "NSE extension point OK"
}

# The built app's IDs come from APP_ID in AppID.xcconfig.
# Usage: check_app_ids <scheme> <destination> <app Info.plist inside App.app> <bundle ID suffix after APP_ID>
check_app_ids() {
  local dir plist app_id got
  dir=$(built_products_dir "$1" "$2") || return 1
  plist="$dir/App.app/$3"
  [ -f "$plist" ] || { echo "app Info.plist not found at $plist" >&2; return 1; }
  app_id=$(sed -nE 's/^[[:space:]]*APP_ID[[:space:]]*=[[:space:]]*([^[:space:]/]+).*/\1/p' "${APP_ID_XCCONFIG:-AppID.xcconfig}" | head -n 1)
  [ -n "$app_id" ] || { echo "APP_ID not found in AppID.xcconfig" >&2; return 1; }
  got=$(plutil -extract CFBundleIdentifier raw "$plist")
  [ "$got" = "$app_id$4" ] || { echo "bundle ID is '$got', want '$app_id$4'" >&2; return 1; }
  got=$(plutil -extract AppGroupID raw "$plist")
  [ "$got" = "group.$app_id" ] || { echo "AppGroupID is '$got', want 'group.$app_id'" >&2; return 1; }
  echo "App ID OK: $app_id$4"
}
