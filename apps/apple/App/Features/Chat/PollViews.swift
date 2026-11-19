import AppCore
import SwiftUI

/// The sheet that makes a poll: a question, 2...12 options and a switch for several answers.
struct PollComposerView: View {
    let send: (Poll) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var question = ""
    /// Each option has a stable ID so removing one row keeps the focus and text of the others.
    @State private var options: [OptionField] = [OptionField(), OptionField()]
    @State private var multi = false

    private struct OptionField: Identifiable {
        let id = UUID()
        var text = ""
    }

    private var poll: Poll? {
        Poll.make(question: question, optionTexts: options.map(\.text), multi: multi)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Question") {
                    TextField("Ask a question", text: $question, axis: .vertical)
                        .onChange(of: question) { _, new in
                            if new.count > Poll.maxQuestion { question = String(new.prefix(Poll.maxQuestion)) }
                        }
                        .accessibilityIdentifier("poll.question")
                }
                Section("Options") {
                    ForEach($options) { $option in
                        HStack {
                            TextField("Option", text: $option.text)
                                .onChange(of: option.text) { _, new in
                                    if new.count > Poll.maxOptionText { option.text = String(new.prefix(Poll.maxOptionText)) }
                                }
                            if options.count > Poll.optionCount.lowerBound {
                                Button {
                                    options.removeAll { $0.id == option.id }
                                } label: {
                                    Image(systemName: "minus.circle.fill").foregroundStyle(.red)
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(Text("Remove option"))
                            }
                        }
                    }
                    if options.count < Poll.optionCount.upperBound {
                        Button("Add option", systemImage: "plus.circle.fill") { options.append(OptionField()) }
                            .accessibilityIdentifier("poll.addOption")
                    }
                }
                Section {
                    Toggle("Allow multiple answers", isOn: $multi)
                }
            }
            .navigationTitle("New poll")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") {
                        if let poll { send(poll); dismiss() }
                    }
                    .disabled(poll == nil)
                    .accessibilityIdentifier("poll.send")
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 380, minHeight: 440)
        #endif
    }
}

/// A poll in a bubble: the question, one row per option with a bar and a count; a tap votes.
struct PollBubbleView: View {
    let messageID: String
    let content: PollContent
    let isMine: Bool
    /// My account ID, to find my own answer.
    let myID: String
    /// Name for an account ID, used by "View votes".
    let name: (String) -> String
    /// Sets my answer: the option IDs I pick, none to take it back.
    let vote: ([String]) -> Void
    @State private var showVotes = false

    private var tally: PollTally { PollTally(content, me: myID) }
    private var ink: Color { isMine ? .white : .primary }

    var body: some View {
        let tally = tally
        VStack(alignment: .leading, spacing: 8) {
            Label {
                Text(content.poll.question).font(.body.weight(.semibold))
            } icon: {
                Image(systemName: "chart.bar")
            }
            .accessibilityAddTraits(.isHeader)
            Text(content.poll.multi ? "Select one or more" : "Select one")
                .font(.caption)
                .foregroundStyle(isMine ? Color.white.opacity(0.8) : Color.secondary)
            ForEach(content.poll.options) { option in
                optionRow(option, tally: tally)
            }
            Button("View votes") { showVotes = true }
                .font(.footnote.weight(.semibold))
                .buttonStyle(.borderless)
                .tint(isMine ? Color.white : Color.accentColor)
                .disabled(tally.voterCount == 0)
                .accessibilityIdentifier("poll.viewVotes.\(messageID)")
        }
        .frame(minWidth: 220, alignment: .leading)
        .sheet(isPresented: $showVotes) {
            PollVotesView(content: content, tally: tally, myID: myID, name: name)
        }
        .accessibilityIdentifier("poll.\(messageID)")
    }

    private func optionRow(_ option: PollOption, tally: PollTally) -> some View {
        let count = tally.counts[option.id] ?? 0
        let picked = tally.mine.contains(option.id)
        return Button {
            vote(tally.picks(afterTapping: option.id, in: content.poll))
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Image(systemName: picked ? (content.poll.multi ? "checkmark.square.fill" : "checkmark.circle.fill")
                                             : (content.poll.multi ? "square" : "circle"))
                    Text(option.text).font(.subheadline).multilineTextAlignment(.leading)
                    Spacer(minLength: 8)
                    Text("\(count)").font(.subheadline.monospacedDigit())
                }
                ProgressView(value: tally.fraction(option.id))
                    .tint(isMine ? Color.white : Color.accentColor)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(ink)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(option.text))
        .accessibilityValue(Text("\(count) votes"))
        .accessibilityAddTraits(picked ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("poll.option.\(messageID).\(option.id)")
    }
}

/// Who voted for what, one section per option.
struct PollVotesView: View {
    let content: PollContent
    let tally: PollTally
    let myID: String
    let name: (String) -> String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(content.poll.options) { option in
                    Section {
                        let voters = tally.voters[option.id] ?? []
                        if voters.isEmpty {
                            Text("No votes yet").foregroundStyle(.secondary)
                        } else {
                            ForEach(voters, id: \.self) { account in
                                Text(account == myID ? String(localized: "You") : name(account))
                            }
                        }
                    } header: {
                        HStack {
                            Text(option.text)
                            Spacer()
                            Text("\(tally.counts[option.id] ?? 0) votes")
                        }
                    }
                }
            }
            .navigationTitle("Votes")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        #if os(macOS)
        .frame(minWidth: 360, minHeight: 400)
        #endif
    }
}
