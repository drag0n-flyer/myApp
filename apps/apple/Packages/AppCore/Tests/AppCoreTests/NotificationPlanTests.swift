import Foundation
import Testing
@testable import AppCore

@Suite struct NotificationPlanTests {
    private func plan(muted: Bool = false, locked: Bool = false, missed: Bool = false) -> PlannedNotification {
        NotificationPlan.content(chatID: "c1", sender: "Ana", text: "secret text", muted: muted, appLockOn: locked, missedCall: missed)
    }

    @Test func showsSenderAndTextInOneThreadPerChat() {
        let p = plan()
        #expect(p.title == "Ana")
        #expect(p.body == "secret text")
        #expect(p.threadIdentifier == "c1")
        #expect(p.category == NotificationPlan.messageCategory)
        #expect(p.category == "message")
        #expect(!p.silent)
        #expect(!p.hidesContent)
        #expect(!p.bodyNeedsTranslation)
    }

    @Test func mutedChatIsSilentButStillShowsText() {
        let p = plan(muted: true)
        #expect(p.silent)
        #expect(p.body == "secret text")
    }

    @Test func appLockNamesNobodyAndOffersNoReply() {
        let p = plan(locked: true)
        #expect(p.hidesContent)
        #expect(p.title == "")
        #expect(p.body == "New message")
        #expect(p.bodyNeedsTranslation)
        #expect(p.category == "message.locked")
        #expect(p.threadIdentifier == "c1")
        #expect(!p.title.contains("Ana") && !p.body.contains("secret"))
    }

    @Test func appLockKeepsMutedSilent() {
        #expect(plan(muted: true, locked: true).silent)
        #expect(!plan(muted: false, locked: true).silent)
    }

    @Test func aMissedCallIsNeverSilent() {
        let p = plan(muted: true, missed: true)
        #expect(!p.silent)
        #expect(p.body == "Missed call")
        #expect(p.bodyNeedsTranslation)
        #expect(p.title == "Ana")
    }

    @Test func aMissedCallWithAppLockHidesTheCaller() {
        let p = plan(muted: true, locked: true, missed: true)
        #expect(!p.silent)
        #expect(p.title == "")
        #expect(p.body == "Missed call")
        #expect(p.category == "message.locked")
    }

    @Test func lockedFormForDeliveredNotificationsIsQuietAndHidden() {
        let p = NotificationPlan.lockedForm(chatID: "c9")
        #expect(p.hidesContent && p.silent)
        #expect(p.title == "" && p.body == "New message")
        #expect(p.threadIdentifier == "c9")
        #expect(p.category == "message.locked")
    }

    @Test func callTitleHidesTheCallerWithAppLock() {
        #expect(NotificationPlan.callTitle(callerName: "Ana", appLockOn: false) == "Ana")
        #expect(NotificationPlan.callTitle(callerName: "Ana", appLockOn: true) == "")
    }

    @Test func replyIsSentOnlyForAKnownOrdinaryChatWithinTheLimit() {
        func d(known: Bool = true, request: Bool = false, blocked: Bool = false, length: Int = 5, locked: Bool = false) -> ReplyDecision {
            NotificationPlan.replyDecision(chatKnown: known, isRequest: request, isBlocked: blocked, textLength: length, appLockOn: locked)
        }
        #expect(d() == .send)
        #expect(d(length: 4096) == .send)
        #expect(d(length: 1) == .send)
        #expect(d(known: false) == .refuseKeepDraft)
        #expect(d(request: true) == .refuseKeepDraft)
        #expect(d(blocked: true) == .refuseKeepDraft)
        #expect(d(length: 4097) == .refuseKeepDraft)
        #expect(d(length: 0) == .refuseKeepDraft)
        #expect(d(locked: true) == .refuseKeepDraft)
    }

    @Test func badgeCountsUnreadChatsThatAreNeitherMutedNorArchived() {
        let unread = ["a": 3, "b": 1, "c": 0, "d": 2, "e": 5]
        #expect(NotificationPlan.badgeCount(unread: unread, muted: ["b"], archived: ["d"]) == 2)
        #expect(NotificationPlan.badgeCount(unread: [:], muted: [], archived: []) == 0)
        #expect(NotificationPlan.badgeCount(unread: ["a": -1], muted: [], archived: []) == 0)
    }

    @Test func aRequestShowsContentButOffersNoReply() {
        let p = NotificationPlan.content(chatID: "c1", sender: "Ana", text: "hi", muted: false, appLockOn: false, isRequest: true)
        #expect(p.category == "message.locked")
        #expect(!p.hidesContent)
        #expect(p.title == "Ana" && p.body == "hi")
        let locked = NotificationPlan.content(chatID: "c1", sender: "Ana", text: "hi", muted: false, appLockOn: true, isRequest: true)
        #expect(locked.hidesContent && locked.title == "")
    }

    @Test func flushWaitIsBoundedByFiveSecondsAndTheBudget() {
        #expect(NotificationPlan.flushWait(remaining: .seconds(20)) == .seconds(5))
        #expect(NotificationPlan.flushWait(remaining: .seconds(5)) == .seconds(5))
        #expect(NotificationPlan.flushWait(remaining: .seconds(2)) == .seconds(2))
        #expect(NotificationPlan.flushWait(remaining: .zero) == .zero)
        #expect(NotificationPlan.flushWait(remaining: .seconds(-3)) == .zero)
    }

    @Test func lockSwitchedOnRewritesWhatNamesSomebody() {
        func r(_ id: String = "x", category: String = "", thread: String = "", call: Bool = false) -> LockedRewrite {
            NotificationPlan.lockedRewrite(identifier: id, category: category, threadID: thread, hasCallID: call)
        }
        #expect(r(category: "message", thread: "c1") == .hideMessage(chatID: "c1"))
        #expect(r(category: "message.locked", thread: "c1") == .hideMessage(chatID: "c1"))   // a request still shows content
        #expect(r(thread: "c1") == .hideMessage(chatID: "c1"))   // no category
        #expect(r("not-sent-c1", thread: "c1") == .hideName)
        #expect(r("not-sent-c1") == .hideName)
        #expect(r("call1", category: "CALL", thread: "c1", call: true) == .keep)   // a live ring may name the caller
        #expect(r("call1", thread: "c1", call: true) == .hideName)   // a missed call may not
        #expect(r("call1", call: true) == .hideName)
        #expect(r("new-device-1", thread: "devices") == .keep)
        #expect(r("other") == .keep)
        #expect(r("x", thread: " ") == .keep)
    }

    @Test func aChatIsReadWhenItsUnreadCountFallsToZero() {
        let before = ["a": 2, "b": 0, "c": 1, "d": 3]
        let after = ["a": 0, "b": 0, "c": 4]   // d left the list: not "read", see leftChats
        #expect(NotificationPlan.readChats(before: before, after: after) == ["a"])
    }

    @Test func aChatThatLeftOrWasBlockedLosesItsNotifications() {
        #expect(NotificationPlan.leftChats(before: ["a", "b"], after: ["a"], blockedBefore: [], blockedAfter: []) == ["b"])
        #expect(NotificationPlan.leftChats(before: ["a", "b"], after: ["a", "b"], blockedBefore: [], blockedAfter: ["b"]) == ["b"])
        #expect(NotificationPlan.leftChats(before: ["a"], after: ["a"], blockedBefore: ["a"], blockedAfter: ["a"]).isEmpty)
    }
}

@Suite struct NotificationRouteTests {
    @Test func takesTheChatFromTheThread() {
        #expect(NotificationRoute.chatID(userInfoChatID: nil, threadID: "c1") == "c1")
    }

    @Test func userInfoAndThreadMustAgree() {
        #expect(NotificationRoute.chatID(userInfoChatID: "c1", threadID: "c1") == "c1")
        #expect(NotificationRoute.chatID(userInfoChatID: "c1", threadID: "c2") == nil)
        #expect(NotificationRoute.chatID(userInfoChatID: "c1", threadID: "") == "c1")
    }

    @Test func rejectsBlankOrOversizeIDs() {
        #expect(NotificationRoute.chatID(userInfoChatID: nil, threadID: "") == nil)
        #expect(NotificationRoute.chatID(userInfoChatID: nil, threadID: "  ") == nil)
        #expect(NotificationRoute.chatID(userInfoChatID: nil, threadID: String(repeating: "x", count: 257)) == nil)
        #expect(NotificationRoute.chatID(userInfoChatID: nil, threadID: "devices") == nil)
    }
}

@Suite struct ReplyBookkeepingTests {
    @Test func theSameReplyIsClaimedOnce() {
        var claims = ReplyClaims()
        let first = claims.claim(requestID: "r1", text: "hi")
        let again = claims.claim(requestID: "r1", text: "hi")
        let otherText = claims.claim(requestID: "r1", text: "other")
        let otherRequest = claims.claim(requestID: "r2", text: "hi")
        #expect(first)
        #expect(!again)
        #expect(otherText)
        #expect(otherRequest)
    }

    @Test func claimsStayBounded() {
        var claims = ReplyClaims()
        for i in 0..<1000 { _ = claims.claim(requestID: "r\(i)", text: "t") }
        #expect(claims.count <= ReplyClaims.limit)
    }

    @Test func aKeptReplyJoinsTheDraftOnANewLine() {
        #expect(ReplyDraft.appending("hi", to: nil) == "hi")
        #expect(ReplyDraft.appending("hi", to: "") == "hi")
        #expect(ReplyDraft.appending("hi", to: "  \n") == "hi")
        #expect(ReplyDraft.appending("hi", to: "first") == "first\nhi")
        #expect(ReplyDraft.appending("hi", to: "first\n") == "first\nhi")
    }

    @Test func aReplyIsSentOnlyWhenItWasQueued() {
        #expect(ReplyDraft.outcome(decision: .send, queued: true) == .sent)
        #expect(ReplyDraft.outcome(decision: .send, queued: false) == .keepDraft)
        #expect(ReplyDraft.outcome(decision: .refuseKeepDraft, queued: true) == .keepDraft)
    }
}

@Suite struct NotificationSettingsTests {
    @Test func roundTrips() {
        let s = NotificationSettings(mutedChatIDs: ["a", "b"], appLockOn: false)
        #expect(NotificationSettings.decode(s.encoded()) == s)
    }

    @Test func unreadableSettingsCountAsLockedWithNothingMuted() {
        let locked = NotificationSettings(mutedChatIDs: [], appLockOn: true)
        #expect(NotificationSettings.decode(nil) == locked)
        #expect(NotificationSettings.decode(Data("not json".utf8)) == locked)
        #expect(NotificationSettings.decode(Data("{\"mutedChatIDs\":[\"a\"]}".utf8)) == locked)
        #expect(NotificationSettings.decode(Data("{}".utf8)) == locked)
    }

    @Test func requestChatsRoundTripAndAnOldFileWithoutThemStillReads() {
        let s = NotificationSettings(mutedChatIDs: [], appLockOn: false, requestChatIDs: ["r"])
        #expect(NotificationSettings.decode(s.encoded()) == s)
        let old = NotificationSettings.decode(Data("{\"mutedChatIDs\":[\"a\"],\"appLockOn\":false}".utf8))
        #expect(old == NotificationSettings(mutedChatIDs: ["a"], appLockOn: false))
        #expect(old.requestChatIDs.isEmpty)
    }

    @Test func aFlagOfWrongTypeCountsAsLocked() {
        #expect(NotificationSettings.decode(Data("{\"mutedChatIDs\":[],\"appLockOn\":\"no\"}".utf8)).appLockOn)
    }
}
