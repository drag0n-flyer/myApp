package org.example.app.ui.chatinfo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.example.app.R
import org.example.app.core.chats.ChatStore
import org.example.app.core.chats.MessageContent
import org.example.app.core.groups.GroupInfo
import org.example.app.core.groups.GroupNameRule
import org.example.app.core.groups.GroupRole
import org.example.app.core.groups.Member
import org.example.app.core.groups.MemberAction
import org.example.app.core.groups.MemberActions
import org.example.app.core.groups.MemberOrder
import org.example.app.core.groups.MyGroupState
import org.example.app.ui.Avatar
import org.example.app.ui.keyString
import org.example.app.ui.usernameText

/**
 * Chat info for a group chat: photo, name, member count, the members with their roles (owner, admins, then members by
 * name), the per-member menu by [MemberActions], Add members, Disappearing messages, Mute, Media and files, and Leave
 * group. Owner and admins edit the name and photo and add people. The encryption line stays here, the only place it is
 * shown. After leaving, the page says so and offers Delete chat. [onOpenMember] opens that person's one-to-one chat page
 * ([safetyNumber] true) or the chat itself (false).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    store: ChatStore,
    chatId: String,
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenMember: (accountId: String, safetyNumber: Boolean) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chats by store.chats.collectAsStateWithLifecycle()
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val muted by store.mutedChatIds.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    val chat = remember(chats, revision) { store.chat(chatId) }
    val info = chat?.group
    val scope = rememberCoroutineScope()
    val locale = LocalLocale.current.platformLocale

    var adding by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var choosingPhoto by rememberSaveable { mutableStateOf(false) }
    var pickTimer by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var ownerFirst by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var removeTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var ownerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    val someone = stringResource(R.string.group_someone)
    fun nameOf(id: String): String =
        if (id == store.me.id) store.me.displayName
        else store.contact(id)?.displayName ?: profiles[id]?.name?.takeIf { it.isNotBlank() } ?: someone

    /** Runs one change; a refusal or a failure says so, nothing else changes. */
    fun change(block: suspend () -> Boolean) {
        failed = false
        scope.launch {
            val done = try { block() } catch (e: Exception) { false }
            if (!done) failed = true
            revision++
        }
    }

    if (info == null) {
        Scaffold(modifier = modifier, topBar = { InfoBar(onBack) }) { }
        return
    }
    val inGroup = info.myState == MyGroupState.Member
    val myRole = if (inGroup) info.roleOf(store.me.id) else null
    val title = chat.title.orEmpty().ifEmpty { stringResource(R.string.group_untitled) }

    if (adding && inGroup && myRole?.canAdd == true) {
        AddMembersPicker(
            store, info, nameOf = ::nameOf,
            onDone = { picked ->
                adding = false
                change { store.addMembers(chatId, picked) }
            },
            onBack = { adding = false },
            modifier = modifier,
        )
        return
    }

    if (editingName && inGroup && myRole?.canEditInfo == true) {
        // A full screen, not a dialog window: the field keeps the keyboard and the screen reader's focus in one place.
        BackHandler { editingName = false }
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.group_edit_name)) },
                    navigationIcon = { IconButton(onClick = { editingName = false }) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) } },
                )
            },
        ) { padding ->
            GroupNameForm(
                chat.title.orEmpty(), onCancel = { editingName = false },
                onSave = { name -> editingName = false; change { store.renameGroup(chatId, name) } },
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    Scaffold(modifier = modifier, topBar = { InfoBar(onBack) }) { padding ->
        val sorted = remember(info.members, locale, profiles) { MemberOrder.sorted(info.members, ::nameOf, locale) }
        LazyColumn(Modifier.padding(padding)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Avatar(title, size = 84.dp, colorKey = chatId)
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        pluralStringResource(R.plurals.group_member_count, info.members.size, info.members.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!inGroup) Text(stringResource(R.string.group_no_longer), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (failed) {
                        Text(
                            stringResource(R.string.group_change_failed), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                HorizontalDivider()
            }
            if (myRole?.canEditInfo == true) {
                item {
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { editingName = true },
                        leadingContent = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.group_edit_name)) },
                    )
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { choosingPhoto = true },
                        leadingContent = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.profile_change_photo)) },
                    )
                    if (profiles[chatId]?.photo != null) {
                        ListItem(
                            modifier = Modifier.clickable(role = Role.Button) { change { store.setGroupPhoto(chatId, null) } },
                            leadingContent = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.profile_remove_photo)) },
                        )
                    }
                    HorizontalDivider()
                }
            }
            if (myRole?.canAdd == true) {
                item {
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { adding = true },
                        leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(stringResource(R.string.group_add_members), color = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.group_members_header), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(sorted, key = { it.accountId }) { member ->
                val isMe = member.accountId == store.me.id
                val contact = store.contact(member.accountId)
                val actions = MemberActions.forMember(
                    myRole, member, isMe, canMessage = contact != null, canVerify = contact != null && store.supportsSafetyNumbers,
                )
                MemberRow(
                    name = nameOf(member.accountId), member = member, actions = actions,
                    onAction = { action ->
                        when (action) {
                            MemberAction.Message -> onOpenMember(member.accountId, false)
                            MemberAction.ViewSafetyNumber -> onOpenMember(member.accountId, true)
                            MemberAction.MakeAdmin -> change { store.setRole(chatId, member.accountId, GroupRole.Admin) }
                            MemberAction.RemoveAdmin -> change { store.setRole(chatId, member.accountId, GroupRole.Member) }
                            MemberAction.MakeOwner -> ownerTarget = member.accountId
                            MemberAction.Remove -> removeTarget = member.accountId
                        }
                    },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClick = onOpenGallery),
                    headlineContent = { Text(stringResource(R.string.info_media_and_files)) },
                    trailingContent = { Text(chat.messages.count { it.content is MessageContent.Photo || it.content is MessageContent.File }.toString()) },
                )
                HorizontalDivider()
                val canTime = myRole?.canSetTimer == true
                ListItem(
                    modifier = if (canTime) Modifier.clickable(role = Role.Button) { pickTimer = true } else Modifier,
                    leadingContent = { Icon(painterResource(R.drawable.ic_timer), contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.info_disappearing)) },
                    trailingContent = { Text(stringResource(DisappearingChoices.labelFor(chat.timerSeconds))) },
                )
                HorizontalDivider()
                val isMuted = chatId in muted || chat.muted
                ListItem(
                    modifier = Modifier.toggleable(isMuted, role = Role.Switch) { store.setMuted(chatId, it); revision++ },
                    leadingContent = { Icon(painterResource(R.drawable.ic_bell_off), contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.group_mute)) },
                    trailingContent = { Switch(checked = isMuted, onCheckedChange = null) },
                )
                HorizontalDivider()
                if (chat.modeMismatch) {
                    ListItem(
                        leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text(stringResource(R.string.info_mismatch), color = MaterialTheme.colorScheme.error) },
                    )
                } else {
                    val line = keyString(chat.encryption.infoKey)
                    ListItem(
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = line },
                        leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                        headlineContent = { Text(line) },
                    )
                }
                HorizontalDivider()
                if (inGroup) {
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) {
                            if (myRole == GroupRole.Owner && info.members.size > 1) ownerFirst = true else confirmLeave = true
                        },
                        headlineContent = { Text(stringResource(R.string.group_leave), color = MaterialTheme.colorScheme.error) },
                    )
                } else {
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { confirmDelete = true },
                        headlineContent = { Text(stringResource(R.string.group_delete_chat), color = MaterialTheme.colorScheme.error) },
                    )
                }
            }
        }
    }

    GroupPhotoChooser(show = choosingPhoto, onDismiss = { choosingPhoto = false }, onPhoto = { jpeg -> change { store.setGroupPhoto(chatId, jpeg) } })

    if (pickTimer) {
        DisappearingDialog(chat.timerSeconds, onDismiss = { pickTimer = false }) { seconds ->
            pickTimer = false
            if (seconds != chat.timerSeconds) store.setTimer(chatId, seconds)
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.group_leave_title)) },
            text = { Text(stringResource(R.string.group_leave_body)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; change { store.leaveGroup(chatId) } }) { Text(stringResource(R.string.group_leave)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (ownerFirst) {
        AlertDialog(
            onDismissRequest = { ownerFirst = false },
            title = { Text(stringResource(R.string.group_leave_owner_title)) },
            text = { Text(stringResource(R.string.group_leave_owner_body)) },
            confirmButton = { TextButton(onClick = { ownerFirst = false }) { Text(stringResource(R.string.group_ok)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.group_delete_title)) },
            text = { Text(stringResource(R.string.group_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; store.deleteChat(chatId); onDeleted() }) { Text(stringResource(R.string.group_delete_chat)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    removeTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.group_remove_title, nameOf(id))) },
            text = { Text(stringResource(R.string.group_remove_body)) },
            confirmButton = { TextButton(onClick = { removeTarget = null; change { store.removeMember(chatId, id) } }) { Text(stringResource(R.string.group_act_remove)) } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    ownerTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { ownerTarget = null },
            title = { Text(stringResource(R.string.group_make_owner_title, nameOf(id))) },
            text = { Text(stringResource(R.string.group_make_owner_body)) },
            confirmButton = { TextButton(onClick = { ownerTarget = null; change { store.setRole(chatId, id, GroupRole.Owner) } }) { Text(stringResource(R.string.group_act_make_owner)) } },
            dismissButton = { TextButton(onClick = { ownerTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.info_title)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) } },
    )
}

/**
 * One member: tile, name and role label. The row reads "Name, role" as one item; the menu is the overflow button and, for
 * a screen reader, the row's custom actions. Nothing is offered on oneself or where [actions] is empty.
 */
@Composable
private fun MemberRow(name: String, member: Member, actions: List<MemberAction>, onAction: (MemberAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val role = when (member.role) {
        GroupRole.Owner -> stringResource(R.string.group_role_owner)
        GroupRole.Admin -> stringResource(R.string.group_role_admin)
        GroupRole.Member -> null
    }
    val described = if (role == null) name else stringResource(R.string.group_member_desc, name, role)
    val labels = actions.map { it to stringResource(actionLabel(it)) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f)
                .then(if (actions.isEmpty()) Modifier else Modifier.clickable(role = Role.Button) { open = true })
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = described
                    customActions = labels.map { (action, label) -> CustomAccessibilityAction(label) { onAction(action); true } }
                },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(name, colorKey = member.accountId)
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
            if (role != null) Text(role, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actions.isNotEmpty()) {
            Column {
                IconButton(onClick = { open = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.group_member_actions, name))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for ((action, label) in labels) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onAction(action) })
                    }
                }
            }
        }
    }
}

private fun actionLabel(action: MemberAction): Int = when (action) {
    MemberAction.Message -> R.string.group_act_message
    MemberAction.ViewSafetyNumber -> R.string.group_act_safety_number
    MemberAction.MakeAdmin -> R.string.group_act_make_admin
    MemberAction.RemoveAdmin -> R.string.group_act_remove_admin
    MemberAction.MakeOwner -> R.string.group_act_make_owner
    MemberAction.Remove -> R.string.group_act_remove
}

/** The group name: 1 to 50 code points under the display-name rules; Save stays off until it holds. */
@Composable
internal fun GroupNameForm(current: String, onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(current) }
    val clean = GroupNameRule.clean(text)
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = capGroupName(it) }, label = { Text(stringResource(R.string.group_name)) },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { clean?.let(onSave) }, enabled = clean != null) { Text(stringResource(R.string.group_save)) }
        }
    }
}

/** The field never holds more than the 50 code points a name may have. */
internal fun capGroupName(text: String): String =
    if (text.codePointCount(0, text.length) <= GroupNameRule.MAX) text else text.substring(0, text.offsetByCodePoints(0, GroupNameRule.MAX))

/** Contacts who are not in the group, to tick; "Add" sends them. At most as many as the group has room for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMembersPicker(
    store: ChatStore, info: GroupInfo, nameOf: (String) -> String, onDone: (List<String>) -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val inGroup = remember(info.members) { info.members.map { it.accountId }.toSet() }
    val candidates = remember(contacts, inGroup, locale) {
        val collator = java.text.Collator.getInstance(locale)
        contacts.filter { it.id !in inGroup }.sortedWith(compareBy(collator) { it.displayName })
    }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val room = info.roomLeft()
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_add_members)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.group_add_none), Modifier.weight(1f).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(candidates, key = { it.id }) { person ->
                        val on = person.id in picked
                        ListItem(
                            modifier = Modifier.toggleable(value = on, role = Role.Checkbox) { picked = if (it) picked + person.id else picked - person.id },
                            leadingContent = { Avatar(person.displayName, colorKey = person.id) },
                            headlineContent = { Text(person.displayName, maxLines = 1) },
                            supportingContent = { Text(usernameText(person.username)) },
                            trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                        )
                    }
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picked.size > room) {
                    Text(stringResource(R.string.group_add_room, room), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { onDone(picked) }, enabled = picked.isNotEmpty() && picked.size <= room, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.group_add_confirm))
                }
            }
        }
    }
}
