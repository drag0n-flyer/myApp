# NSE prototype: physical iPhone checklist

Each item is one line. Mark pass or fail.

- [ ] Install `NSEProtoHost` on a physical iPhone and launch it once with the `-prepare` argument (this writes `push.apns` into the app group container).
- [ ] Lock the iPhone, then send `push.apns`: Xcode, Window, Devices and Simulators, Send push; or `curl` to the APNs sandbox with the owner's key (never commit that key).
- [ ] The notification shows `hello from MLS` while the phone is locked after first unlock.
- [ ] `nse-result.txt` in the app group container starts with `OK hello from MLS` and reports `mem=` above 0.
- [ ] After a reboot, without unlocking, send the push again: the fallback `New message` is shown (expected).
- [ ] Instruments, Allocations, attached to the extension: peak memory stays below 24 MB.
- [ ] On the device, `LiveMLSEngineTests.testStorageProtectionClass` passes (the simulator has no data protection, so CI skips it).
