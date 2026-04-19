# Toychain-Miner

A Java-based blockchain mining program developed for the **Blockchain and Distributed Systems module**.

##  Overview

This program mines blocks for a toy blockchain by finding a nonce such that the SHA-256 hash meets a required difficulty (leading zero bits).

##  Features

- Multi-threaded mining
- Adjustable difficulty
- Random nonce generation
- Automatic block recording
- SHA-256 hashing

##  Tech Stack

- Java
- SHA-256 (MessageDigest)
- Multithreading (Thread, Atomic, CountDownLatch)

## ▶️ How to Run

```bash
javac Miner.java
java Miner <prevHash> <pseudonym> <startDifficulty> <targetDifficulty> [threads]
