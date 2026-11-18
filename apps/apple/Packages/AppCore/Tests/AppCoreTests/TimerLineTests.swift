import Testing
@testable import AppCore

@Suite struct TimerLineTests {
    @Test func ownLineNamesTheChoice() {
        #expect(SystemEvent.timer(seconds: 86_400, name: nil).timerLine == TimerLine(choice: .day, name: nil))
        #expect(SystemEvent.timer(seconds: 604_800, name: nil).timerLine == TimerLine(choice: .week, name: nil))
        #expect(SystemEvent.timer(seconds: 7_776_000, name: nil).timerLine == TimerLine(choice: .quarter, name: nil))
    }

    @Test func otherPersonsLineKeepsTheName() {
        #expect(SystemEvent.timer(seconds: 604_800, name: "Ana").timerLine == TimerLine(choice: .week, name: "Ana"))
    }

    @Test func zeroIsOff() {
        #expect(SystemEvent.timer(seconds: 0, name: "Ana").timerLine == TimerLine(choice: .off, name: "Ana"))
    }

    @Test func aNumberThatIsNoChoiceHasNoLine() {
        #expect(SystemEvent.timer(seconds: 60, name: nil).timerLine == nil)
    }

    @Test func otherEventsAreNoTimerLine() {
        #expect(SystemEvent.safetyNumberChanged.timerLine == nil)
    }

    @Test func choiceDurationsAreWordedInDays() {
        #expect(TimerChoice.day.days == 1)
        #expect(TimerChoice.week.days == 7)
        #expect(TimerChoice.quarter.days == 90)
        #expect(TimerChoice.off.days == 0)
    }
}
