# Sourced by test-ios.sh / test-macos.sh. Usage: check_nse <scheme> <destination> <appex Info.plist glob inside App.app>
check_nse() {
  local settings dir plist got
  # Capture first: piping xcodebuild into an early-exiting reader can SIGPIPE under pipefail.
  settings=$(xcodebuild -project App.xcodeproj -scheme "$1" -configuration Debug -destination "$2" -showBuildSettings 2>/dev/null) || true
  dir=$(printf '%s\n' "$settings" | awk -F' = ' '/^ *BUILT_PRODUCTS_DIR = /&&!d{d=$2} END{print d}')
  [ -n "$dir" ] || { echo "BUILT_PRODUCTS_DIR not found" >&2; return 1; }
  plist=$(ls "$dir"/App.app/$3 2>/dev/null | head -1) || true
  [ -n "$plist" ] || { echo "NSE Info.plist not found under $dir/App.app/$3" >&2; return 1; }
  got=$(plutil -extract NSExtension.NSExtensionPointIdentifier raw "$plist")
  [ "$got" = "com.apple.usernotifications.service" ] || { echo "NSE extension point is '$got'" >&2; return 1; }
  echo "NSE extension point OK"
}
