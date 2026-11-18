import Foundation
import Testing
@testable import AppCore

private func event(_ line: GroupSystemLine) -> SystemEvent { .group(line) }

@Test func groupLinesSayWhatChangedWithAndWithoutAnActor() {
    #expect(event(.added(by: "Ana", who: "Bob")).text == "Ana added Bob")
    #expect(event(.added(by: nil, who: "Bob")).text == "Bob was added")
    #expect(event(.removed(by: "Ana", who: "Bob")).text == "Ana removed Bob")
    #expect(event(.removed(by: nil, who: "Bob")).text == "Bob was removed")
    #expect(event(.left(who: "Bob")).text == "Bob left")
    #expect(event(.youWereRemoved).text == "You were removed")
    #expect(event(.madeAdmin(who: "Bob")).text == "Bob is now an admin")
    #expect(event(.noLongerAdmin(who: "Bob")).text == "Bob is no longer an admin")
    #expect(event(.nameChanged(by: "Ana", name: "Club")).text == "Ana changed the group name to “Club”")
    #expect(event(.nameChanged(by: nil, name: "Club")).text == "The group name was changed to “Club”")
    #expect(event(.photoChanged(by: "Ana")).text == "Ana changed the group photo")
    #expect(event(.photoChanged(by: nil)).text == "The group photo was changed")
}

@Test func aGroupLineSurvivesStorage() throws {
    for line in [GroupSystemLine.added(by: "a", who: "b"), .removed(by: nil, who: "b"), .left(who: "b"), .youWereRemoved,
                 .madeAdmin(who: "b"), .noLongerAdmin(who: "b"), .nameChanged(by: "a", name: "N"), .photoChanged(by: nil)] {
        let data = try JSONEncoder().encode(SystemEvent.group(line))
        #expect(try JSONDecoder().decode(SystemEvent.self, from: data) == .group(line))
    }
}

@Test func namesInAGroupLineAreResolvedButTheGroupNameIsNot() {
    let upper: (String) -> String = { $0.uppercased() }
    #expect(GroupSystemLine.added(by: "a", who: "b").resolved(upper) == .added(by: "A", who: "B"))
    #expect(GroupSystemLine.removed(by: nil, who: "b").resolved(upper) == .removed(by: nil, who: "B"))
    #expect(GroupSystemLine.nameChanged(by: "a", name: "club").resolved(upper) == .nameChanged(by: "A", name: "club"))
    #expect(GroupSystemLine.youWereRemoved.resolved(upper) == .youWereRemoved)
}

@Test func aChatIsReadOnlyOnlyWhenIAmNoLongerInTheGroup() {
    #expect(GroupMyState.member.isReadOnly == false)
    #expect(GroupMyState.removed.isReadOnly && GroupMyState.left.isReadOnly)
    #expect(GroupMyState(wire: "removed") == .removed && GroupMyState(wire: "left") == .left)
}
