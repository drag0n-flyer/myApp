package org.example.app.core.links

import kotlin.test.Test
import kotlin.test.assertEquals

class ContactLinkTest {
    private val host = "home.example.org"
    private val token = "AbCdEfGhIjKlMnOpQrSt-_"

    private fun parse(url: String) = ContactLink.parse(url, host)

    @Test fun user() = assertEquals(ContactLink.User("alice_1"), parse("https://home.example.org/u/alice_1"))
    @Test fun invite() = assertEquals(ContactLink.Invite(token), parse("https://home.example.org/c/$token"))
    @Test fun otherHost() = assertEquals(ContactLink.OtherServer, parse("https://other.example.org/u/alice"))
    @Test fun otherHostInvite() = assertEquals(ContactLink.OtherServer, parse("https://other.example.org/c/$token"))
    @Test fun httpIsNotALink() = assertEquals(ContactLink.NotALink, parse("http://home.example.org/u/alice"))
    @Test fun badUsername() {
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/u/Al"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/u/Alice"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/u/1alice"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/u/"))
    }
    @Test fun tokenLength() {
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/c/" + token.dropLast(1)))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/c/${token}A"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/c/" + token.dropLast(1) + "="))
    }
    @Test fun trailingSlashAndExtraSegment() {
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/u/alice/"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/u/alice/x"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/c/$token/x"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/x/u/alice"))
    }
    @Test fun upperCaseHost() = assertEquals(ContactLink.User("alice"), parse("https://HOME.Example.ORG/u/alice"))
    @Test fun queryAndFragmentIgnored() {
        assertEquals(ContactLink.User("alice"), parse("https://home.example.org/u/alice?x=1#top"))
        assertEquals(ContactLink.Invite(token), parse("https://home.example.org/c/$token#frag"))
    }
    @Test fun portMustMatch() {
        assertEquals(ContactLink.User("alice"), ContactLink.parse("https://home.example.org:8443/u/alice", "home.example.org:8443"))
        assertEquals(ContactLink.OtherServer, ContactLink.parse("https://home.example.org:9/u/alice", "home.example.org:8443"))
        assertEquals(ContactLink.OtherServer, ContactLink.parse("https://home.example.org/u/alice", "home.example.org:8443"))
    }
    @Test fun notLinks() {
        assertEquals(ContactLink.NotALink, parse(""))
        assertEquals(ContactLink.NotALink, parse("alice"))
        assertEquals(ContactLink.NotALink, parse("https:///u/alice"))
        assertEquals(ContactLink.NotALink, parse("https://home.example.org/u/alice b"))
        assertEquals(ContactLink.NotALink, parse("https://user@home.example.org/u/alice"))
    }
    @Test fun surroundingSpacesAreTrimmed() = assertEquals(ContactLink.User("alice"), parse("  https://home.example.org/u/alice\n"))

    @Test fun explicit443IsNoPort() {
        assertEquals(ContactLink.User("alice"), ContactLink.parse("https://home.example.org:443/u/alice", "home.example.org"))
        assertEquals(ContactLink.User("alice"), ContactLink.parse("https://home.example.org/u/alice", "home.example.org:443"))
        assertEquals(ContactLink.User("alice"), ContactLink.parse("https://home.example.org:443/u/alice", "HOME.example.org:443"))
        assertEquals(ContactLink.OtherServer, ContactLink.parse("https://home.example.org:444/u/alice", "home.example.org:443"))
    }
}
