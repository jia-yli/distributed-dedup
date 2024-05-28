# README


## Build and Test

### Dependencies

#### Necessary Environment Compnents

1. SpinalHDL is a Scala library, and Scala is running on JVM. So we need to install JDK.
2. Build tool. We use Mill for this project(which will download and call it automatically by using script `./mill`) and SBT to re-build SpinalCrypto by another Scala compiler version
2. Verification can be done in a Scala-native way, we only need to provide verilator backend for it.

```Bash
sudo apt-get install openjdk-17-jdk # Get Java JDK
# sudo apt-get install sbt # Get SBT, no Scala needed. This will not work because SBT is not in the resolving path of apt-get
sudo apt-get install build-essential # Need make
sudo apt-get install verilator # Get verilator for simulation
```

#### Install SBT
1. (Recommended, easy and clean)By cs setup, in this way, cs(itself), scala, sbt will be installed under
`${HOME}/.local/share/coursier/bin/`, and this path will be added to `${HOME}/.profile`.
From https://www.scala-lang.org/download/

```Bash
# for x86-64 architecture
curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz | gzip -d > cs && chmod +x cs && ./cs setup
# for arm64
# curl -fL https://github.com/VirtusLab/coursier-m1/releases/latest/download/cs-aarch64-pc-linux.gz | gzip -d > cs && chmod +x cs && ./cs setup
# add ${HOME}/.local/share/coursier/bin/ to PATH
source ${HOME}/.profile
```

2. According to SpinalHDL documentation(https://spinalhdl.github.io/SpinalDoc-RTD/dev/SpinalHDL/Getting%20Started/getting_started.html#requirements-things-to-download-to-get-started), following steps are needed to install sbt via apt-get. But we can only get much older version of SBT and it is ugly(since this method needs some deprecated feature)
```Bash
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee
/etc/apt/sources.list.d/sbt.list
echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee
/etc/apt/sources.list.d/sbt_old.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?
op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt-get update
sudo apt-get install sbt
```


#### SpinalCrypto (Need to re-build with another Scala version)
[Github Repo](https://github.com/SpinalHDL/SpinalCrypto)

1. Choose correct Scala compiler versionin `SpinalCrypto/build.sbt`, L9 change to: scalaVersion := CryptoVersion.scalaCompilers(1),
2. Build and publish SpinalCryto locally by `sbt publishLocal` under `SpinalCrypto/`. The lib path will be in `~/.ivy2/local/com.github.spinalhdl/`

### Usage
#### Tests
Run all tests. Sometimes this may fail and Verilator waveform all 0. It is recommended to run tests individually.
```bash
./mill hwsys.test
```

Run specific test
```Bash
./mill hwsys.test.testSim dedup.hashtable.HashTableLookupFSMTests
```

#### Generate Verilog
First check `hwsys/src/dedup/WrapDedupCore.scala` for configurations, especially the hash table configurations are the one you want 

Generate Verilog under `generated_rtl/`
```Bash
# ./mill will download(if not exist) and call correct version of Mill
./mill hwsys.runMain dedup.GenDedupSys
# Then move the folder to target position under coyote in remote server.
./sync_remote_v.sh
```

## FPGA Deployment
### Coyote setup(with RDMA)

<!-- https://github.com/rbshi/coyote/tree/dev_dlm -->
https://github.com/jia-yli/coyote-rdma

```Bash
# build coyote
git clone https://github.com/jia-yli/coyote-rdma.git

cd coyote/hw
mkdir build && cd build

# use desired number memory channel: -DN_MEM_CHAN
# cmake options are under hw/examples.cmake, be sure to use correct mem channels: set(N_CARD_AXI ${NUM_FSM + 1}), where NUM_FSM is the number of FSM in hash table, +1 for the memory manager.
cmake .. -DFDEV_NAME=u55c -DEXAMPLE=perf_dedup

# use screen session on build server
screen
make shell && make compile
# coyote done
```