# Tandem
At the moment it seems that t:slim X2 won't have capabilities to support closed loop, so that part
of the project is put on hold now. I refactored code, so that Mobi code Slim code are separate where
needed and common where it isn't. Project is now targeting Tandem Mobi device and if at some point
Slim will support all required remote commands, that part of project can be resurected and made
work with current code.

Dev version:  3.4.0.0-dev
Dev Date:     04. Jan 2026

# Versioning

v0.7.x - Phase 4 (beta at some point)
v0.6.x - Phase 3
v0.5.28.2 - Phase 2



# Tandem Mobi

Work on Mobi is in progress. Planned are 5 phases of development:

Phase 1: Base framework, configuration commands, set/get profile and set/get TBR (oref0), will need controlX2
         for management actions, QualifyingEvents (just display keywords)
Phase 2: Bolus, adding all needed UI elements (needs to be done in Compose) and functionality for
         management (all actions done by controlX2: start/stop pump, change reservoir, fill cannula),
         start work on History -Reading, maybe also storing into Db, look into Notification, QE to add into Db,
         pump reconect whem disconnected (31.6.)
Phase 3: History UI, readHistory after commands (and sync with real Ids), PumpInfo UI, QuickBolus settings
         After this phase is done, open to other users to start testing
         (31.8)
Phase 4: More on history and Bugfixing, adding security fixes (31.9)

Phase 5: Prepare to add to /dev, clean code, final documentation





# Tandem Slim X2

At the time of creation of project there were only versions of pump available that don't support
any kind of looping (Tandem is extending pump with bolus and hoping something more)

### Tandem Slim X2
For successful looping pump must support at least 2 commands: setting TBR and setting Bolus. At
the moment (according to official sources), Slim X2 will support only one of them, which will
make Closed-Loop impossible. So if you have Slim X2, you will be able to Open Loop only (unless
they add support for TBR too), but you be able to receive all information from the pump and AAPS
will give you pointers what to do.

Most of work that will be done here, will be precursor for next device (t:Sport or t:Mobi), which
 will support everything.



CHANGE LOG
----------

0.6.x - Phase 3
0.5.x - Phase 2

tandem-0.2.6-SNAPSHOT
0.2.6 - updated to 3.2.
0.2.5 - moved and renamed packages
0.2.4 - Merging with dev-g (9.2.2023)
0.2.3 - Pairing sort of works, start of history parsing


***

## Opened Issues (Mobi)



		- QE:
			- QualifyingEventHandler - description - phase 3            1 PT (not sure if we need this)
		    - filter events (configuration = show only AAPS 			3 PT
		          relevant events from pump) - phas


		- Communication:
			- timeout													1 PT
		    - prevent connect											2 PT (not sure if this is needed at least on that level)
		    - 1 minute status task - prevent connect					4 PT


		- Bolus:
			- Cancel Bolus												5 PT
			- Bolus Security											5 PT
			- Bolus Status Reading should be timed						5 PT


		- TBR:
			- TBR security												5 PT
			- Cancel TBR when Canula actions							5 PT


		- UI:
			- Look into notifications: NotificationBundle.allRequests() 4 PT
OK			- disable/hide last pump event on main UI [v0.6.12.5]		1 PT


		- Site Reminder
			- UI														10 PT
			- integrate into notifications								1 PT
			- integrate into driver										2 PT


		- reconnect if error Pump Crictical								15 PT


		- Pairing wizard												20 PT


		Bugs:
		- Pump reset connection (reconnect)                             5 PT
OK		- Buttons disabled [v0.6.12.0]									1 PT




***

## How to help test (Outdated)

Project is not ready to be tested yet, but for some prelimiminary testing 
here are simple instructions.


Prepare Test Phone (if you don't have one)
1. Get a Phone (Android needs to be higher than 9, recommended that version is also lower than 12)
2. If you have done Objectives before import those settings, if you have Live AAPS you can use
   that. Change Nightscout URL first (we don't want test setup to send data to NS), then do
   export, and change URL back
3. Import old setting

Prepare APS
1. Download this repository (put it into separate folder than your running/live version) in this
   branch and build AAPS (name it something like AndroidAPS_Tandem) -
   https://github.com/andyrozman/AndroidAPS.git  (you neet to checkout andy_tandem branch)
2. Deploy to your Phone, recommended to use Android < 12
3. Follow instructions from developer on what to test. (You need at least Bolus-IQ enabled
   version, It needs to have Tandem Pump API at version 2.2)
4. Easiest way to deploy, is to connect your phone to your computer and run it from Android Studio,
   you will be able to see what is happening if you look into Logcat tab (logs)








