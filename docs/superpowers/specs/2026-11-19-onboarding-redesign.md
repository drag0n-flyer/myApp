# Onboarding redesign (all apps)

Lead's spec, 2026-11-19. The flow and copy of design §6.7–6.8 (mockup 05) stay; this changes how
the screens are built and laid out, and how sign-up errors are handled. Owner asked for it after
the v0.1.0 Mac build showed iPhone screens stretched across a Mac window and a misleading
"Something went wrong on the server" during sign-up.

## Why the sign-up error happened
The deployed server is healthy. The app shows `.server` whenever the server answers
`not_found`, `forbidden` or `expired` for the sign-up (unknown, unverified, already used or
timed-out sign-up), and when the passkey step is retried after the sign-up was used up. Those
are not server faults; the user only needs to start the sign-up again.

## Goals
- Every onboarding screen looks native on its platform: iPhone, Mac, Android phone and tablet.
- No error the user can't act on. Every error sits next to what caused it and says what to do.
- A build that can't use passkeys never shows the passkey step; it uses the email code.

Non-goals: new steps, new server endpoints, recovery key (Phase 2), changing the flow order.

## Shared rules (all platforms)
1. **Layout.** Content column max width 460 pt/dp, centred. Title (large, bold, leading),
   one-line message (secondary), then the content. Primary action at the bottom of the column.
2. **Back.** A system navigation back control in the bar, never a floating circle.
3. **Errors.** Shown inline under the field or above the primary button, in the system error
   style, with an icon. Never centred on an empty screen.
4. **Sign-up expired.** New error `signupExpired`, text: "This sign-up has expired. Start again."
   with a "Start again" button that returns to Create account with the name, username and email
   kept. Used when the sign-up passkey-options or finish call returns `not_found`, `forbidden`
   or `expired`.
5. **No passkey support.** When the app can't use passkeys for the server, Verify email
   finishes the sign-up with the email code and goes straight to Permissions. "I already have
   an account" → "Sign in with an email code" directly (no passkey button).
6. **Passkey copy.** iPhone: "No password to remember. Your passkey is saved in iCloud Keychain,
   so your other Apple devices can sign in too." Mac: same text. "Sign in on a new phone" becomes
   "Sign in on a new device". The passkey-unavailable text becomes "Passkeys can't be used here.
   Use an email code instead."
7. **Busy state.** While a request runs, the primary button shows a progress indicator and is
   disabled; fields stay visible.
8. Keyboard: Return moves to the next field and submits on the last one. Code fields use
   one-time-code content type.

## Apple (iPhone and Mac, apps/apple/App/Features/Onboarding)
- `OnboardingView` hosts the steps in a `NavigationStack`; Back is a toolbar button
  (`.navigation` placement on Mac, leading on iPhone) with a VoiceOver label "Back". Remove
  `GlassCircleButton` here.
- Replace `PrimaryButtonStyle` and `OnboardingField` with system components:
  `.buttonStyle(.borderedProminent)` + `.controlSize(.large)`; fields in a `Form` with
  `.formStyle(.grouped)` sections, section footers for help text and inline errors.
- iPhone: primary button full width, pinned to the bottom safe area.
- Mac: window content 460 pt column centred in the window, minimum window size 560×600 while
  onboarding. Buttons are not full width: primary button trailing-aligned with
  `.keyboardShortcut(.defaultAction)`, secondary actions to its leading side; Back in the
  toolbar. Welcome: icon, title, pitch, the three points, then "Get started" and
  "I already have an account" side by side, centred.
- Window title: `AppBrand.name` on the welcome step, the step title on the other steps.
- ServerKit `LiveAccountService.map`: add a `signup: Bool` parameter like `link`; sign-up calls
  map `not_found`/`forbidden`/`expired` to `.signupExpired`. Add `AccountError.signupExpired`
  in AppCore. Tests in ServerKit for each of the three codes, and that other calls still map to
  `.server`/`.codeExpired` as before.
- `AccountError+Text`: add the new text; reword as in rule 6.

## Android (apps/android/app/.../ui/onboarding)
- Each step is a `Scaffold` with a Material 3 `TopAppBar` (navigation icon `ArrowBack`,
  content description "Back"); welcome has no bar.
- Content column `widthIn(max = 460.dp)` centred, so tablets and foldables don't stretch.
- `OutlinedTextField` with `supportingText` for help and `isError` + error text for errors.
- Primary `Button` full width of the column at the bottom (`imePadding`), secondary as
  `TextButton`. Busy state: `CircularProgressIndicator` inside the button.
- Same `signupExpired` mapping in the account layer and the same rules 4–6. When Credential
  Manager has no passkey provider or the server isn't a passkey server, skip the passkey step.
- Strings in strings.xml; Robolectric/Compose tests for: back bar present on every step but
  welcome, signupExpired returns to Create account with fields kept, passkey step skipped when
  unsupported, error shown under its field.

## Owner review list
The error mapping touches the sign-up (authentication) path: client-side mapping only, no
server, key or crypto change. Merged after agent review per the owner's deferral; listed for
the owner's review.
