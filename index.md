![license](https://img.shields.io/badge/Platform-Android-green "Android")
![license](https://img.shields.io/badge/Version-Beta-yellow "Version")
![license](https://img.shields.io/badge/Licence-Apache%202.0-blue.svg "Apache")


## Table of Contents
[Introduction](#introduction)

[Codebase Organization](#codebase-organization)
 - [Continous Monitoring Infrastructure](#continous-monitoring-infrastructure)

 - [System-level Mobility Management Enhancement](#system-level-mobility-management-enhancement)

 - [App-level Mobility Management Enhancement](#app-level-mobility-management-enhancement)

[Platform Requirements](#platform-requirements)

[Data Release](#data-release)

[For Developers](#for-developers)

## Introduction
This repository contains our continuous monitoring infrastructure (based on a customized Android system dubbed Android-MOD) for recording fine-grained observability data when users engage with mobile apps using underground cellular networks, as well as our efforts for mitigating Video Stream Stalls (VSSes) in subways on Android devices. We also release a portion of our measurement data (with proper anonymization). Our latest Android-MOD system is built upon vanilla Android 13/14. Therefore, you'll be able to run codes in this repo by patching these modifications to proper framework components.

### [The entire codebase and sample data are available in our [Github repo](https://github.com/UndergroundCellular/UndergroundCellular.github.io).]

### Continous Monitoring Infrastructure
Our modifications to the vanilla Android mainly consist of three parts, as shown in [monitor](https://github.com/UndergroundCellular/UndergroundCellular.github.io/tree/main/monitor).
First, we add [`SubwayDetection.java`](https://github.com/UndergroundCellular/UndergroundCellular.github.io/blob/main/monitor/SubwayDetection.java) to detect when a device boards a subway. 
Second, once the device is confirmed to be in a subway environment, 
    Android-MOD initiates our dedicated data recording service ([`SubwayDataCollection.java`](https://github.com/UndergroundCellular/UndergroundCellular.github.io/blob/main/monitor/SubwayDataCollection.java)) to log the network states we are concerned with (as listed [here](#data-release)).
Third, to monitor the frame rate of the foreground app and detect VSSes, we modify the graphics layer compositor of Android
    by adding a timer task (`MonitorFrameRate`) in [`SurfaceFlinger.cpp`](https://github.com/UndergroundCellular/UndergroundCellular.github.io/blob/main/monitor/SurfaceFlinger.cpp)
    and updating `onPostComposition` in [`Layer.cpp`](https://github.com/UndergroundCellular/UndergroundCellular.github.io/blob/main/monitor/Layer.cpp) for the statistics of composed layers.

| File | Key Changes | Purpose | Location in AOSP|
| ---- | ---- | ---- | ---- |
|SubwayDetection.java | A new class | Indentify subway scenarios | `packages/services/Telephony/src/com/android/phone/SubwayDetection.java`|
|SubwayDataCollection.java | A new class | Collect cross-layer data | `packages/services/Telephony/src/com/android/phone/SubwayDataCollection.java`|
|SurfaceFlinger.cpp| `MonitorFrameRate` (added)  | Count the number of composed graphics layers from the foreground app | `frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp`|
|Layer.cpp| `onPostComposition` (changed) | Notify `SurfaceFlinger` when a graphic layer from the foreground app is composed | `frameworks/native/services/surfaceflinger/Layer.cpp`|


### System-level Mobility Management Enhancement
We currently provide our Time-Inhomogeneous State Space Model (TISSM) that characterizes the underground signal attenuation, enabling Android's cellular management to find the optimal handover timing.
As shown in [TISSM-Model](https://github.com/UndergroundCellular/UndergroundCellular.github.io/tree/main/TISSM-Model), Android-MOD updates the TISSM-based signal attenuation model when new signal strength measurements are obtained.
Leveraging the model, the system then estimates future signal attenuation to determine whether to trigger handover.

### App-level Mobility Management Enhancement
We provide the implementation and training code for our Multi-Tower Fusio Model (MTFM) [here](https://github.com/UndergroundCellular/UndergroundCellular.github.io/blob/main/mtfm/train.py).
Integrated into the [SubwayDataCollection](https://github.com/UndergroundCellular/UndergroundCellular.github.io/blob/main/monitor/SubwayDataCollection.java) class, the model predicts upcoming VSSes in real-time and enables apps to take proactive measures to prevent them from actually happening.

## Platform Requirements
For Android-related modifications, currently our code is run and tested in Android 13 and Android 14 (AOSP).
Note that despite quite a number of changes have been made in Android 14 since Android 13, our code is applicable to both given that concerned tracing points remain unchanged.

## Data Release
We provide a portion of data recorded when mobile apps are active in foreground in subway environments for references [here](https://github.com/UndergroundCellular/UndergroundCellular.github.io/blob/main/sample_dataset/sample_data.csv), including critical network, cellular, and device information:

| Information | Description |
| ---- | ---- |
| `UID` | Unique ID generated to identify a user (cannot be related to the user's true indentity) |
| `TIME` | UNIX timestamp when recording starts |
| `OS` | Android vesion |
| `MODEL` | Device model |
| `APN`   | Current access point names |
| `APP`   | The foreground app |
| `DATA` | Detailed data recorded every second during the measurement, with each second's data arranged in the following order: round-trip time (RTT), packet loss rate, DNS query success rate, DNS query latency, uplink bandwidth, downlink bandwidth, Radio Access Technology (RAT), Reference Signal Received Power (RSRP), Signal-to-Noise Ratio (SNR), Cell Identity (CID), and the VSS label (1 for VSS and 0 for non-VSS). Here we only present data around VSS occurrences.|


## For Developers
Our code is licensed under Apache 2.0 in accordance with AOSP's license. Please adhere to the corresponding open source policy when applying modifications and commercial uses.




