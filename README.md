tela
====

`tela` is a web-based communications client, built on top of XMPP. It's at an extremely early stage of development (think pre-pre-pre alpha :-), but current features include basic one-to-one text chat and video chat. Future features will include personal data storage and the ability to share data between users.

###Requirements
 - ejabberd 14.07 XMPP server.
 - JDK7 or later.
 - sbt 0.13.6
 - bower

`tela` does not yet have a standalone build, it must be run from a development environment.

###Configuring the server
The `tela.conf` file in the root directory of the source tree is used to configure the server.

Create a directory (preferably outside of the source tree) that will be used by the system to store user data (currently just profile info). Point the `root-directory` setting in `tela.conf` to this location.

Install and configure your XMPP server, and create a few users as per the server's documentation. Thus far, the system has only been tested with the XMPP server running on the same machine as `tela`, using an unsecured XMPP connection. For a deployment like this, the only other setting that should need to be changed in `tela.conf` is the `domain` setting in `xmpp-config`, which should be set to whatever domain was chosen during configuration of the XMPP server.

###Building and running from sbt
`tela` expects the JVM default `file.encoding` property to be UTF8. When using sbt, the easiest way to achieve this (if it's not already the default on your system) is to set the `JAVA_TOOL_OPTIONS` environment variable to `-Dfile.encoding=UTF8`

Unfortunately there is a manual step that needs to be performed at the moment, which is to run `bower install` from the root of the source tree, in order to install the javascript libraries used by `tela`.

Run sbt from the root directory, then it should be possible to run all tests using `test`. To run the `tela` server, type `project runner` followed by `run`.

###Building and running from IntelliJ
In theory IntelliJ's "Import from sbt" feature should be usable, in practice I have found this problematic in both IntelliJ 13 and 14.

What works best is to run `gen-idea` from the sbt command prompt. This will generate IntelliJ 13 project files. Opening the project in IntelliJ 14 will then lead to the project being converted to IntelliJ 14 format.

As with sbt, `file.encoding` should be set to UTF8. This can (AFAIK) be done by adding `-Dfile.encoding=UTF8` to the VM options in the Run configuration dialog.

As when running with sbt, `bower install` should be run from the command line prior to running the server in order to load javascript dependencies.

Some of the unit tests will fail if they are not run from the project root  directory (as opposed to the root of their particular module). The default works fine in IntelliJ 13, but in 14 I had to change the Working Directory option in the JUnit default run configuration from $MODULE_DIR$ to $PROJECT_DIR$

The main class for running the server is `tela.runner.Tela`. Again, this should be run from the project root directory (this should happen by default).

###Usage
Once both the XMPP server and `tela` are running, it should be possible to use a browser to connect to the server on the port specified in `tela.conf`. It should then be possible to log in using one of the users configured on the XMPP server (using the bare username, so just "user" rather than "user@domain".

Note: Currently (as a development convenience) a window less than 980 pixels in size will be interpreted as a "mobile" device, and will invoke `tela`'s experimental mobile interface (all of `tela` is experimental, but the mobile interface is even more experimental :-)

Contacts can be added via the contact list pane, with the following two caveats:
 - Fully qualified usernames should be used, i.e. "user@domain", not just "user"
 - For presence to work both ways, users must add each other. For example, when `user1` adds `user2`, `user1` will appear in `user2`'s contact list (as well as vice versa, naturally), but for `user2`to see `user1`'s presence, `user2` must explicitly add `user1`.

All interactions are currently initiated via drag and drop. To initiate a video call with another user, drag their username from the contact list onto the video chat pane. To initiate a text chat, drag onto the text chat pane. On mobile devices, drag the username to the grey box at the bottom of the screen, and you will be prompted which app to drop onto. Note that it is assumed that on mobile devices this will be done with using touch - mouse based drag/drop doesn't work for this.

A user can fill in their profile info using the profile info pane. To view another user's profile (this only works if using ejabberd as the server right now), drag the username from the contact list to the view profile tab.

Yes, I realise that the UI looks more like a construction site than a piece of software, we will describe plans to overhaul it in our roadmap document which is coming Real Soon Now :-)

###Miscellaneous gotchas
Both sides of the conversation have to click “Hang Up” to properly end a video call

Changing language and resetting password don’t work on Firefox due to a bug in the HTTP server being used (plans are afoot to move to Play Framework)
