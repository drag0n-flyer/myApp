import AppCore
import SwiftUI

/// What the person typed on Create account. Kept here so "Start again" returns with the fields filled.
@MainActor @Observable final class SignUpDraft {
    var name = ""
    var username = ""
    var email = ""
}

/// Hosts the sign-up steps in a navigation stack. Back is a toolbar button; the flow keeps its own history.
struct OnboardingView: View {
    @Bindable var model: AppModel
    @State private var draft = SignUpDraft()

    var body: some View {
        NavigationStack {
            step
                .toolbar {
                    if model.onboarding.step != .welcome {
                        ToolbarItem(placement: backPlacement) {
                            Button("Back", systemImage: "chevron.backward") {
                                withAnimation { model.onboarding.back() }
                            }
                            .accessibilityIdentifier("onboarding.back")
                        }
                    }
                }
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar(model.onboarding.step == .welcome ? .hidden : .automatic, for: .navigationBar)
                #endif
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 600)
        #endif
        .animation(.default, value: model.onboarding.step)
    }

    private var backPlacement: ToolbarItemPlacement {
        #if os(macOS)
        .navigation
        #else
        .topBarLeading
        #endif
    }

    @ViewBuilder private var step: some View {
        switch model.onboarding.step {
        case .welcome: WelcomeView(model: model)
        case .joinServer: JoinServerView(model: model)
        case .inviteCode: InviteCodeView(model: model)
        case .createAccount: CreateAccountView(model: model, draft: draft)
        case .verifyEmail: VerifyEmailView(model: model)
        case .createPasskey: PasskeyView(model: model)
        case .permissions: PermissionsView(model: model)
        case .linkDevice: LinkDeviceView(model: model)
        case .lostDevice: LostDeviceView(model: model)
        case .done: EmptyView()
        }
    }
}

/// Widest the content gets; wider windows centre it.
let onboardingColumnWidth: CGFloat = 460

private struct OnboardingHeader: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.largeTitle.weight(.bold)).accessibilityAddTraits(.isHeader)
            if let message {
                Text(message).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The primary action at the bottom of the column, secondary actions beside it (Mac) or under it (iPhone).
private struct OnboardingActions<Primary: View, Secondary: View>: View {
    @ViewBuilder let primary: Primary
    @ViewBuilder let secondary: Secondary

    var body: some View {
        #if os(macOS)
        HStack {
            secondary.controlSize(.large)
            Spacer()
            primary
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .keyboardShortcut(.defaultAction)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .frame(maxWidth: onboardingColumnWidth)
        .frame(maxWidth: .infinity)
        #else
        VStack(spacing: 8) {
            primary
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
            secondary.controlSize(.large)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
        .frame(maxWidth: onboardingColumnWidth)
        .frame(maxWidth: .infinity)
        #endif
    }
}

/// Title, message, free-form content, and the actions: the layout every step without fields shares.
struct OnboardingPage<Content: View, Primary: View, Secondary: View>: View {
    let title: LocalizedStringKey
    var message: LocalizedStringKey?
    let content: Content
    let primary: Primary
    let secondary: Secondary

    init(title: LocalizedStringKey, message: LocalizedStringKey? = nil,
         @ViewBuilder content: () -> Content, @ViewBuilder primary: () -> Primary,
         @ViewBuilder secondary: () -> Secondary) {
        self.title = title
        self.message = message
        self.content = content()
        self.primary = primary()
        self.secondary = secondary()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                OnboardingHeader(title: title, message: message)
                content
            }
            .padding(.horizontal, 20)
            .padding(.top, 24)
            .frame(maxWidth: onboardingColumnWidth)
            .frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) {
            OnboardingActions(primary: { primary }, secondary: { secondary })
        }
        #if os(macOS)
        .navigationTitle(title)
        #endif
    }
}

extension OnboardingPage where Secondary == EmptyView {
    init(title: LocalizedStringKey, message: LocalizedStringKey? = nil,
         @ViewBuilder content: () -> Content, @ViewBuilder primary: () -> Primary) {
        self.init(title: title, message: message, content: content, primary: primary, secondary: { EmptyView() })
    }
}

/// Same layout with the content in a grouped form: use `Section`s with footers for help text and errors.
struct OnboardingFormPage<Content: View, Primary: View, Secondary: View>: View {
    let title: LocalizedStringKey
    var message: LocalizedStringKey?
    let content: Content
    let primary: Primary
    let secondary: Secondary

    init(title: LocalizedStringKey, message: LocalizedStringKey? = nil,
         @ViewBuilder content: () -> Content, @ViewBuilder primary: () -> Primary,
         @ViewBuilder secondary: () -> Secondary) {
        self.title = title
        self.message = message
        self.content = content()
        self.primary = primary()
        self.secondary = secondary()
    }

    var body: some View {
        Form {
            Section {
                OnboardingHeader(title: title, message: message)
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            content
        }
        .formStyle(.grouped)
        .frame(maxWidth: onboardingColumnWidth)
        .frame(maxWidth: .infinity)
        .safeAreaInset(edge: .bottom) {
            OnboardingActions(primary: { primary }, secondary: { secondary })
        }
        #if os(macOS)
        .navigationTitle(title)
        #endif
    }
}

extension OnboardingFormPage where Secondary == EmptyView {
    init(title: LocalizedStringKey, message: LocalizedStringKey? = nil,
         @ViewBuilder content: () -> Content, @ViewBuilder primary: () -> Primary) {
        self.init(title: title, message: message, content: content, primary: primary, secondary: { EmptyView() })
    }
}

/// A primary button label that shows a progress indicator while a request runs. Disable the button too.
struct BusyLabel: View {
    let title: LocalizedStringKey
    let busy: Bool

    var body: some View {
        ZStack {
            Text(title).opacity(busy ? 0 : 1)
            if busy { ProgressView() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(title))
    }
}
