# ledger-wallet-daemon &middot; 

[![CircleCI](https://circleci.com/gh/LedgerHQ/ledger-wallet-daemon.svg?style=shield)](https://circleci.com/gh/LedgerHQ/ledger-wallet-daemon)
[![codecov](https://codecov.io/gh/LedgerHQ/ledger-wallet-daemon/branch/develop/graph/badge.svg)](https://codecov.io/gh/LedgerHQ/ledger-wallet-daemon)
[![Generic badge](https://img.shields.io/badge/Version-2.4.3-blue)](https://shields.io/)


## Run Integration Tests with coverage locally
 ```bash
sbt clean coverage it:test coverageReport
```

Once generated, an html report can be found at `target/scala-2.12/scoverage-report/index.html`

## Updating the libcore

When a new version of the libcore is available, we need to update our bindings.

| Prerequisite                                   | Remarks |
| ============================================== | ========================================= |
| [docker](https://www.docker.com/get-started)   | Only needed if you plan to build in local |
| [sbt](https://www.scala-sbt.org/download.html) |                                           |

1. Check out [lib core project](https://github.com/LedgerHQ/lib-ledger-core)
   ```bash
   git clone --recurse-submodules --depth 1 --branch <branch_or_version_tag> https://github.com/LedgerHQ/lib-ledger-core
   ```

2. Copy `build-jar.sh` and `build-jar-linux.sh` to the lib core project folder
   ```bash
   cp $WALLET_DAEMON_FOLDER/build-jar.sh $LIB_CORE_FOLDER
   cp $WALLET_DAEMON_FOLDER/build-jar-linux.sh $LIB_CORE_FOLDER
   ```

3. `cd` to lib core folder
   ```bash
   cd $LIB_CORE_FOLDER
   ```

4. Two possible situations: either you want a fresh build from your local machine, or you want to
   grab the official lib core artifacts.
   1. For building on your machine, run the script `build-jar.sh` with command: `mac`, `linux` or `all`.
      MacOS can build both `mac` and `linux`. Linux can only build `linux`.
      ```bash
      # Build for mac only. You may want to do this when developing,
      # it's much faster than build for both mac and linux
      bash build-jar.sh mac

      # Build for linux. Linux build is using docker.
      bash build-jar.sh linux

      # Build for both mac and linux
      bash build-jar.sh all
      ```
   2. For getting the official release, pass an extra argument `dl` to the command invocation.
      ```bash
      # Get only the mac release artifacts.
      ./build-jar.sh mac dl

      # Get only the linux artifacts.
      ./build-jar.sh linux dl

      # Get both.
      ./build-jar.sh all dl
      ```

5. Then, you find the jar file in `$LIB_CORE_FOLDER/../build-jar/target/scala-<version>/build-jar-<version>.jar`.
   Replace the `ledger-lib-core.jar` in the lib folder with this file.
   ```bash
   mv $LIB_CORE_FOLDER/../build-jar/target/scala-<version>/build-jar-<version>.jar $WALLET_DAEMON_FOLDER/lib/ledger-lib-core.jar
   ```

6. Push the changes to upstream

7. Add a tag on the new commit with the version of the ledger-core-lib, or the commit
   hash if no version was tagged

## Using Wallet Daemon with PostgreSQL

### Setup

1. Make sure to have a PostgreSQL server running,

2. For integration tests make sure to create required databases, you could use the `db.sh` for that:

    ```
    ./dbs.sh <host> <port>
    ```
    To drop databases simply run: 
    ```
    ./dbs.sh <host> <port> drop
    ```

3. For tests with users' databases (i.e. `database.db` created), please use, in an analogous way, `client_dbs.sh` script.

### Configuration


1. By default, Wallet Daemon is using Sqlite3 by default, to activate PostgreSQL set: 
    ```
    CORE_DATABASE_ENGINE=postgres
    ``` 
2. Setting the PostgreSQL URL connection is done through: `PG_URL`, by default set to `postgres://localhost:5432`,    
3. It is possible to set the connection pool size per wallet pool (e.g. by client) thanks to: 
`PG_CONNECTION_POOL_SIZE_PER_WALLET_POOL`, which is equal to `2` by default. 


