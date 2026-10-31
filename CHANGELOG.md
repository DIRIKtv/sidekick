# Changelog

All notable changes to Sidekick. Each version's notes are also on its
[GitHub release](https://github.com/DIRIKtv/sidekick/releases).

## 1.8.5 · October 31, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Better captions:** they now roll like TV captions, the newest two lines on a dark glass card, staying up through short pauses instead of blinking off. They follow your TV's caption text size and move above the controls when those appear

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- Captions disappeared between sentences and came back repeating words already said
- In Recently watched, the green focus ring covered the LIVE badge, and the line under each name was cut off

## 1.8.4 · October 31, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **One look throughout the app:** the player's dark glass style, with its fine outline, now runs everywhere: the side menu, Settings rows and menus, the search box and keyboard, recent searches, channel page buttons and tabs, the Home banner's chips, thumbnail badges, dialogs and pop-ups. Whatever you select still turns solid green

## 1.8.3 · October 31, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Player buttons match the title card:** dark glass with a fine outline, so they're easy to see even over bright video. Chat shows a green ring while it's on

## 1.8.2 · October 31, 2026

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- The progress bar is back to its slim look when selected (the time bubble and seek previews stay)
- Player messages, such as the sleep timer or quality you just picked, could be hidden behind the title card at the top; they now appear just below it

## 1.8.1 · October 31, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Up next when a stream ends:** instead of a plain message, a full-screen card shows what to watch next: the channel they raided, one of your live followed channels, or a popular stream in the same category. It starts by itself after 10 seconds (any button stops it), or turn that off in Settings → Playback
- **Seek previews:** scrubbing a past broadcast shows a picture of where you'll land, in a bubble above the progress bar. Turn it off in Settings → Playback

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Bolder progress bar:** it thickens when selected, and a bubble shows the time you'd jump to (or how far behind live)
- **Header card:** the stream title, channel and avatar sit in one tidy card, with a green heart when you follow the channel
- **One style for player messages:** Paused, quality changes, follows and seeking now all drop in at the top centre
- **Friendlier errors:** plain-English explanations instead of error codes, with two clear choices such as Try again or Lower quality

## 1.8.0 · October 31, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Updated networking and images:** OkHttp 5 (everything Sidekick fetches from Kick and GitHub, and the video itself) and Coil 3.6 (thumbnails, avatars and emotes)
- **Ready for Android 17:** Sidekick now targets the latest Android version and is built with Kotlin 2.4 and the latest Android build tools

## 1.7.9 · October 31, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Updated video player:** Sidekick now uses Media3 1.11 (up from 1.8), with three releases' worth of playback and streaming fixes from Google

## 1.7.8 · October 31, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **More room under the channel tabs,** so the grid no longer crowds Past broadcasts and Clips
- **Updated libraries:** newer AndroidX, Kotlin coroutines and QR code libraries, built with the latest Android tools

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- The selected Watch live or Follow button on a channel page still had its top and bottom edges cut off

## 1.7.7 · October 31, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Channel tabs:** Past broadcasts and Clips now sit right under Watch live and Follow. Move onto a tab to switch, and press OK to jump into it
- **Current stream card:** while a channel is live, its first card is the live stream itself instead of the in-progress recording
- **Sign in from the menu:** the account at the bottom of the side menu now says Sign in and can be selected to sign in, or to open your account's options

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **New loading animation:** a swirling ring replaces the old loader everywhere
- **Logo on the phone sign-in page,** and a note on both sign-in screens that Sidekick is unofficial and not affiliated with Kick
- **Smoother on Fire TV:** loading animations, the update download bar and poll bars no longer create garbage every frame, chat restyles in place, and screens no longer paint their background twice
- **Accessibility:** QR codes are described for screen readers, decorative images are skipped, and the LIVE badge text is a little larger

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- Signing out didn't update the name at the bottom of the side menu until the app was reopened
- The Watch live and Follow buttons had their top and bottom edges cut off when selected
- The focus glow on a card could keep a closed screen in memory

## 1.7.6 · October 30, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Chat text size per layout:** Customize layout has a Text tool, so a small chat box can use smaller text and fit more messages. Each channel can keep its own size

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Clearer layout editor:** the tool you're using is highlighted in green, and Backing is now Transparency (right makes chat more see-through)

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- The play, rewind and forward buttons started slightly left and moved to the centre a moment later
- The quality button was slightly wider than the others, with its icon off-centre

## 1.7.5 · October 30, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Put chat anywhere:** in the player, Chat → Customize layout now lets chat over the video go anywhere on screen, at any size (as small as a streamer's on-screen chat), with as much or as little dark backing as you like. Hold an arrow to move faster; a dashed outline shows the chat's edges while you arrange it

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Viewer count in the LIVE pill:** the count now sits beside LIVE at the bottom left, and the top of the player no longer repeats how long the stream has been live
- **Readable see-through chat:** with little or no backing, messages get a soft shadow and the chat title bar steps away
- Saving a chat layout also turns chat on

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- The Pause label sat too close to its button when the controls first opened

## 1.7.4 · October 30, 2026

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- A faint light rim on the left corners of the Home banner, most visible on darker streams

## 1.7.3 · October 30, 2026

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- A thin light edge around the focused Home banner, most visible at its corners on bright streams

## 1.7.2 · October 30, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Instant start from a preview:** pressing OK on a stream that's already previewing (on the Home banner or a card) carries it straight on full screen, no reloading
- **Rows remember your place:** moving up or down returns to the card you were last on in that row, and coming back from the side menu lands where you left
- **Banner preview** fills the banner without stretching, and the slow zoom stops once the stream starts

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- Moving down from the Home banner picked a card in the middle of the row instead of the first
- Holding LEFT at the start of a row could slide down through the rows below
- Light edges at the corners of the focused Home banner
- In the player, a long quality label could push the buttons into each other

## 1.7.1 · October 30, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **The Home banner plays the stream:** rest on it for a moment and the channel plays live, muted, right in the banner

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Redesigned Home banner:** the picture fills the whole banner, with a pulsing live badge, a bigger channel name, a tidier one-line title (links and dividers removed), the category as a tag and a proper Watch button
- **Softer focus on the banner:** it lifts slightly with a green glow instead of a thick outline

## 1.7.0 · October 30, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Arrange chat your way, per channel:** in the player, Chat → Customize layout lets you put chat on the left or right, beside or over the video, and change its width, height and position with the remote. Each channel remembers its own layout, or press ☰ to make it the default
- **Pinned messages, polls and predictions** show above chat, with live results
- **Message spacing and emote size** for chat
- **Quality button** in the player, showing the quality you're watching
- **Progress line** along the bottom of past broadcasts while the controls are hidden
- **Resume or start over** when you reopen a past broadcast you didn't finish
- **Card size** for Home: small, medium or large

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Faster:** the app opens quicker, streams start sooner when you've been resting on them, and Home loads rows as you scroll to them
- **Lighter on memory,** especially on smaller Fire TV sticks
- **Clearer toggles** on the selected setting

## 1.6.3 · October 30, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Easier to read:** the description under a selected setting or menu item is darker and slightly bolder on the green highlight

## 1.6.2 · October 30, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Settings sections slide in:** moving down the sections, the next one rises from below; moving up, it drops in from above, just like the side menu's pages

## 1.6.1 · October 30, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Settings sections:** the highlight glides between sections, like the side menu
- **Smoother side menu edge:** it now blends into the page without a visible line

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- **Watch live button:** on a channel page, its rounded end no longer gets cut off when selected

## 1.6.0 · October 30, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Profile button in the player:** next to LIVE, it takes you straight to the streamer's channel
- **Richer chat from your phone:** badges, 7TV emotes, and a quick emote bar with the channel's emotes, Kick's and 7TV's
- **Clock and account in the side menu,** plus a dot on Settings when an update is waiting

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **A new side menu:** it fades softly into the page, and one highlight glides between items
- **Page transitions:** pages slide in from the direction you move in the menu (turn off in Settings → Appearance)
- **Livelier screens:** a slow zoom on the Home banner, loading outlines instead of spinners, and a gentle glow on the card you're on
- **Little moments:** sparks when you follow, a ripple and running total when you skip, search results cascading in, and a pulse on go-live alerts

## 1.5.1 · October 30, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **A new opening:** the Sidekick logo glows while Home loads, then flies into the side menu as Home slides into place
- **A new update screen:** a live progress bar with the size, time left and a check when it's done, instead of a single "Downloading…" line

## 1.5.0 · October 30, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Chat from your phone:** the new phone button in the player shows a QR code. Scan it to read chat and send messages from your phone. The page follows whatever your TV is playing, so you only scan once
- **Raids:** when the channel you're watching raids someone, Sidekick offers to take you along (or goes automatically, or stays put; Settings → Playback → Raids)
- **Live previews:** rest on a live stream for a moment and it starts playing, muted, right in the card. Pick 1, 2 or 3 seconds, or turn it off, in Settings → Browsing → Previews
- **Chat replay:** past broadcasts play their original chat in time with the video, even when you skip around

## 1.4.1 · October 29, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Live count on Following:** a red number on the Following heart shows how many channels you follow are live
- **Unfollow from the player:** the heart in the player now only shows for channels you don't follow yet; turn on Settings → Playback → Unfollow from the player to bring it back

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Loudness equalization, rebuilt:** quiet talking comes up much more and sudden loud moments are caught straight away, instead of everything being turned down after a shout
- **Faster chat emotes and badges:** a channel's emotes and badges load as soon as you open it, so busy chats show them right away

## 1.4.0 · October 29, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **A new font:** Figtree throughout the app, or pick Inter, Manrope or your TV's own font in Settings → Appearance
- **New Search:** an on-screen keyboard beside the results, voice search where your TV supports it, and results split into live streams, categories and channels. With nothing typed it shows your recent searches and what's popular
- **Hide categories:** keep categories you don't want (like Slots & Casino) off Home, Browse and Search
- **Start-up page:** open the app on Home, Following, Browse or Search
- **Chat timestamps and chat width:** in Settings and in the player's chat menu

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Settings, reorganized:** sections on the left, their settings on the right, with your account picture and clearer icons
- **Simpler sign-in:** just the QR code; scan it and sign in on your phone
- **Text size preview:** see a sample message at each chat text size before picking one
- **Channels without a picture** now show their initial instead of a blank circle
- **Solid player menu:** chat no longer shows through it

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- **Busy chats:** messages no longer pile on top of each other when chat moves fast

## 1.3.3 · October 29, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **New icons:** a cleaner, bolder icon set throughout the app that's easier to see from the couch

## 1.3.2 · October 23, 2026

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **New logo:** a refreshed Sidekick logo on the launcher icon, TV banner, splash screen and sign-in screen

## 1.3.1 · October 23, 2026

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- **Phone sign-in:** the "Code" label no longer runs into the field's border on the verification step
- **Phone sign-in:** the page now shows the Sidekick name

## 1.3.0 · October 23, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **A new name and look:** Sidekick, with a new logo, launcher icon and TV banner
- **Automatic updates setting:** check every time the app opens (default), once a day, or only when you ask
- **About screen** with privacy and license details (Settings → About Sidekick)
- **Open source** under the MIT license

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Instant picture-in-picture:** picking the stream that's playing in the corner expands it without reloading
- **Safer sign-in:** your Kick sign-in is now stored encrypted on the TV

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- **Stuck side menu:** after leaving a stream, focus could get stuck on the menu and keep bouncing back to Home

## 1.2.0 · October 22, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Clips:** today's top clips on Home and popular clips on every channel page
- **Multi-view:** watch up to 4 live channels at once
- **Picture-in-picture:** press Home while watching to keep the stream in a corner
- **TV home screen:** a "Live on Kick" row and Play Next for past broadcasts (Android TV / Google TV)
- **Go-live alerts** when someone you follow starts streaming
- **Continue watching** and **Recently watched** rows on Home
- **Sleep timer** and **loudness equalization** in the player
- **Chat:** 7TV emotes and filters (hide bots and !commands, calm busy chats)

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- **"HTTP 429" errors** when signed in

## 1.1.0 · October 20, 2026

### <img src="docs/icons/new.png" width="20" height="22" align="top"> New
- **Updates from GitHub** right inside the app (Settings → Check for updates)
- **Chat badges** with Kick's icons for mods, VIPs, OGs, founders, subscribers and gifters

### <img src="docs/icons/improved.png" width="20" height="22" align="top"> Improved
- **Smoother chat:** it slides in and out and the video resizes to make room
- **Animations** throughout the app, plus a launch screen
- **Unfollowing** now asks for confirmation

### <img src="docs/icons/fixed.png" width="20" height="22" align="top"> Fixed
- **Sign-in** fixes and Android 16 back-button support

## 1.0.0 · October 20, 2026

- **First release:** live streams, past broadcasts, chat, live rewind, following and sign-in
