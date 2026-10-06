# How to try JagaNet (Windows)

You don't need to install anything yourself.

1. **Download the project.** On the GitHub page of this branch click **Code › Download ZIP**,
   then right-click the ZIP › **Extract All…** and choose a short folder, e.g. `D:\JagaNet`.
2. **Double-click `Start JagaNet.bat`** in that folder.
   If Windows shows "Windows protected your PC", click **More info › Run anyway**.
3. A window with a menu opens. Type a number and press **Enter**:

| Key | What it does |
|---|---|
| **1** | Opens the app on a virtual Android phone on your screen |
| **2** | Opens the app in a phone-sized window on your PC (quickest, always works) |
| **3** | Runs the automatic tests and shows which passed |
| **4** | Takes a screenshot of every screen and opens the folder |
| **5** | Opens an overview of what has been built, the design and the screenshots |
| **6** | Stops everything |
| **0** | Exit |

**The first time takes a while.** Option 1 downloads about 3 GB once (Java, the Android tools
and the virtual phone): 10–30 minutes depending on your internet. Everything goes into
`%LOCALAPPDATA%\JagaNet` (or `C:\JagaNetTools`), not into Windows. Later starts take about a minute.

## In the app

Sign in with one of these e-mails. You don't get a real e-mail: the 6-digit code is shown on screen.

- `alex@example.com` (paid **Pro** user with devices and 30 days of statistics)
- `sam@example.com` (**Free** user who has used most of the monthly data)
- `owner@jaganet.dev` (the **owner**: Settings › Business dashboard shows revenue and server health)

Things to try: tap the big button to connect (the VPN is simulated, your internet isn't
touched), look at Stats, Devices › Add device, Settings › Protocol, Get Pro.

## If something goes wrong

- **"Virtualization is switched off"** (option 1): the script offers to turn it on; restart the PC
  afterwards. Option 2 works meanwhile.
- Anything else: the window says what failed. The full log is in `build\run\launcher.log` inside
  the project folder; send it over.

On a Mac or Linux: `./run.sh`, `./run.sh android` or `./run.sh server` in a terminal.
