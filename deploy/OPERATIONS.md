# OttoChain Operations Guide

**How to evolve, update, and maintain your deployment.**

## Table of Contents
1. [Restarting Layers](#restarting-layers)
2. [Rollback Procedures](#rollback-procedures)
3. [Updating OttoChain Code](#updating-ottochain-code)
4. [Adding Nodes](#adding-nodes)
5. [Removing/Replacing Nodes](#removingreplacing-nodes)
6. [Resetting Genesis](#resetting-genesis)
7. [Monitoring](#monitoring)
8. [Backup & Recovery](#backup--recovery)
9. [Scaling](#scaling)
10. [Upgrading Tessellation](#upgrading-tessellation)

---

## Restarting Layers

### Layer Dependency Order

```
GL0 (Global L0)
  ↓
ML0 (Metagraph L0)
  ↓
┌─────────┬─────────┐
CL1       DL1
(Currency) (Data)
```

**Rule:** Always restart bottom-up. Children depend on parents.

### Restart GL0 Only

```bash
# WARNING: This affects ALL layers - they will reconnect
docker restart gl0

# Wait for GL0 to be ready
sleep 15
curl -s http://localhost:9000/node/info | jq .state

# Other layers should auto-reconnect, but verify:
curl -s http://localhost:9200/node/info | jq .state  # ML0
```

### Restart ML0 Only

```bash
# CL1 and DL1 will temporarily lose their L0 peer
docker restart ml0

sleep 15
curl -s http://localhost:9200/node/info | jq .state

# L1 layers should auto-reconnect
```

### Restart CL1 Cluster

```bash
# Option 1: Rolling restart (maintains consensus)
for node in cl1-2 cl1-1 cl1; do
  docker restart $node
  sleep 15
  echo "$node restarted"
done

# Verify cluster reformed
curl -s http://localhost:9300/cluster/info | jq length

# Option 2: Full cluster restart
docker restart cl1 cl1-1 cl1-2

# Wait and rejoin
sleep 15
curl -X POST "http://localhost:9312/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'
curl -X POST "http://localhost:9322/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'
```

### Restart DL1 Cluster

```bash
# Option 1: Rolling restart
for node in dl1-2 dl1-1 dl1; do
  docker restart $node
  sleep 15
done

# Verify cluster
curl -s http://localhost:9400/cluster/info | jq length

# Option 2: Full cluster restart
docker restart dl1 dl1-1 dl1-2

sleep 15
curl -X POST "http://localhost:9412/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9401}'
curl -X POST "http://localhost:9422/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9401}'
```

### Restart Single L1 Node

```bash
# Restart one node - cluster continues with others
docker restart cl1-1

# It should auto-rejoin, but if not:
sleep 10
curl -X POST "http://localhost:9312/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'
```

### Restart Entire Stack

```bash
#!/bin/bash
# Save as restart-stack.sh

echo "Stopping all layers..."
docker stop dl1-2 dl1-1 dl1 cl1-2 cl1-1 cl1 ml0 gl0

echo "Starting GL0..."
docker start gl0
sleep 15
echo "GL0: $(curl -s http://localhost:9000/node/info | jq -r .state)"

echo "Starting ML0..."
docker start ml0
sleep 15
echo "ML0: $(curl -s http://localhost:9200/node/info | jq -r .state)"

echo "Starting CL1 cluster..."
docker start cl1
sleep 10
docker start cl1-1 cl1-2
sleep 10
curl -s -X POST "http://localhost:9312/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'
curl -s -X POST "http://localhost:9322/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'
echo "CL1: $(curl -s http://localhost:9300/cluster/info | jq length) nodes"

echo "Starting DL1 cluster..."
docker start dl1
sleep 10
docker start dl1-1 dl1-2
sleep 10
curl -s -X POST "http://localhost:9412/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9401}'
curl -s -X POST "http://localhost:9422/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9401}'
echo "DL1: $(curl -s http://localhost:9400/cluster/info | jq length) nodes"

echo "Stack restart complete!"
```

---

## Rollback Procedures

### Rollback JAR Version

```bash
# Keep previous JARs with version suffix
cp /opt/ottochain/jars/data-l1.jar /opt/ottochain/jars/data-l1.jar.v1.0.0
cp /opt/ottochain/jars/data-l1.jar.v0.9.0 /opt/ottochain/jars/data-l1.jar

# Restart affected layer
docker stop dl1 dl1-1 dl1-2
docker rm dl1 dl1-1 dl1-2

# Restart with old JAR (same docker run commands)
# ... start dl1, dl1-1, dl1-2 ...
# ... rejoin cluster ...
```

### Rollback with Data Preservation

```bash
# 1. Stop layer
docker stop dl1 dl1-1 dl1-2

# 2. Backup current data
cp -r /opt/ottochain/data/dl1 /opt/ottochain/data/dl1.backup.$(date +%Y%m%d)
cp -r /opt/ottochain/data/dl1-2 /opt/ottochain/data/dl1-2.backup.$(date +%Y%m%d)
cp -r /opt/ottochain/data/dl1-3 /opt/ottochain/data/dl1-3.backup.$(date +%Y%m%d)

# 3. Restore previous JAR
cp /opt/ottochain/jars/data-l1.jar.previous /opt/ottochain/jars/data-l1.jar

# 4. Restart (data should be compatible if minor version change)
docker rm dl1 dl1-1 dl1-2
# ... start dl1, dl1-1, dl1-2 ...
```

### Rollback with Data Reset

If data format changed between versions:

```bash
# 1. Stop layer
docker stop dl1 dl1-1 dl1-2
docker rm dl1 dl1-1 dl1-2

# 2. Backup and clear data
mv /opt/ottochain/data/dl1 /opt/ottochain/data/dl1.broken
mv /opt/ottochain/data/dl1-2 /opt/ottochain/data/dl1-2.broken
mv /opt/ottochain/data/dl1-3 /opt/ottochain/data/dl1-3.broken
mkdir -p /opt/ottochain/data/{dl1,dl1-2,dl1-3}

# 3. Restore previous JAR
cp /opt/ottochain/jars/data-l1.jar.previous /opt/ottochain/jars/data-l1.jar

# 4. Restart - will sync from ML0
# Node 1 as initial-validator (fresh genesis for this layer)
docker run -d --name dl1 ... java -jar /jars/data-l1.jar run-initial-validator
# Nodes 2,3 as validators
docker run -d --name dl1-1 ... java -jar /jars/data-l1.jar run-validator
docker run -d --name dl1-2 ... java -jar /jars/data-l1.jar run-validator
# Rejoin cluster
```

### Rollback Entire Stack to Backup

```bash
# 1. Stop everything
docker stop gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2
docker rm gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2

# 2. Restore JARs
cp /opt/ottochain/jars.backup/*.jar /opt/ottochain/jars/

# 3. Restore data
rm -rf /opt/ottochain/data/*
tar -xzvf /opt/ottochain/backups/data-YYYYMMDD.tar.gz -C /opt/ottochain/

# 4. Restart stack
./restart-stack.sh
```

### Rollback Services

```bash
cd /opt/ottochain-services

# Checkout previous version
git log --oneline -10  # Find commit to rollback to
git checkout <commit-hash>

# Rebuild
pnpm install
pnpm build

# Restart services
docker compose down
docker compose build
docker compose up -d

# Run migrations (if needed)
docker exec ottochain-services-indexer-1 npx prisma db push
```

### Emergency Rollback Script

```bash
#!/bin/bash
# Save as /opt/ottochain/emergency-rollback.sh

BACKUP_DATE=${1:-$(ls -t /opt/ottochain/backups/*.tar.gz | head -1 | grep -oE '[0-9]{8}')}

echo "Emergency rollback to $BACKUP_DATE"
echo "WARNING: This will stop all services and restore from backup!"
read -p "Continue? (yes/no) " confirm
if [ "$confirm" != "yes" ]; then
  echo "Aborted."
  exit 1
fi

# Stop everything
echo "Stopping all containers..."
docker stop gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2 2>/dev/null
docker rm gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2 2>/dev/null

# Restore JARs
if [ -d "/opt/ottochain/jars.backup.$BACKUP_DATE" ]; then
  echo "Restoring JARs..."
  cp /opt/ottochain/jars.backup.$BACKUP_DATE/*.jar /opt/ottochain/jars/
fi

# Restore data
if [ -f "/opt/ottochain/backups/data-$BACKUP_DATE.tar.gz" ]; then
  echo "Restoring data..."
  rm -rf /opt/ottochain/data/*
  tar -xzvf /opt/ottochain/backups/data-$BACKUP_DATE.tar.gz -C /opt/ottochain/
fi

# Restart
echo "Restarting stack..."
/opt/ottochain/restart-stack.sh

echo "Rollback complete!"
```

### Version Management Best Practices

```bash
# Before any update, create versioned backup
VERSION="1.0.0"
DATE=$(date +%Y%m%d)

# Backup JARs
mkdir -p /opt/ottochain/jars.backup.$DATE
cp /opt/ottochain/jars/*.jar /opt/ottochain/jars.backup.$DATE/

# Backup data
mkdir -p /opt/ottochain/backups
tar -czvf /opt/ottochain/backups/data-$DATE.tar.gz /opt/ottochain/data/

# Tag the backup
echo "$VERSION - $DATE" >> /opt/ottochain/backups/versions.log

# Now safe to update
```

---

## Updating OttoChain Code

### Build New JARs

```bash
# On dev machine
cd /path/to/ottochain
git pull origin main
source ~/.sdkman/bin/sdkman-init.sh

# Clean build
sbt clean "l0/assembly" "l1/assembly" "dataL1/assembly"

# Copy to server
scp modules/l0/target/scala-2.13/*-assembly-*.jar root@<HOST>:/opt/ottochain/jars/metagraph-l0.jar
scp modules/l1/target/scala-2.13/*-assembly-*.jar root@<HOST>:/opt/ottochain/jars/currency-l1.jar
scp modules/data_l1/target/scala-2.13/*-assembly-*.jar root@<HOST>:/opt/ottochain/jars/data-l1.jar
```

### Rolling Update (Zero Downtime for L1)

For L1 layers (CL1, DL1), you can do rolling updates:

```bash
# Update one node at a time
# 1. Stop node 3
docker stop cl1-2
docker rm cl1-2

# 2. Start with new JAR (it will rejoin automatically)
docker run -d --name cl1-2 ... java -jar /jars/currency-l1.jar run-validator

# 3. Wait for it to rejoin cluster
sleep 30
curl -s http://localhost:9300/cluster/info | jq length

# 4. Repeat for nodes 2 and 1
```

### Full Restart (Downtime)

```bash
# Stop all containers in reverse order
docker stop dl1-2 dl1-1 dl1 cl1-2 cl1-1 cl1 ml0 gl0

# Start in order
docker start gl0 && sleep 15
docker start ml0 && sleep 15
docker start cl1 && sleep 10
docker start cl1-1 cl1-2 && sleep 10
# Rejoin CL1 cluster
curl -X POST "http://localhost:9312/cluster/join" ...
curl -X POST "http://localhost:9322/cluster/join" ...

docker start dl1 && sleep 10
docker start dl1-1 dl1-2 && sleep 10
# Rejoin DL1 cluster
curl -X POST "http://localhost:9412/cluster/join" ...
curl -X POST "http://localhost:9422/cluster/join" ...
```

---

## Adding Nodes

### Add a 4th CL1 Node

```bash
# Generate new key
mkdir -p /opt/ottochain/keys5
cd /opt/ottochain/keys5
CL_KEYSTORE=key.p12 CL_KEYALIAS=alias CL_PASSWORD=password \
  java -jar /opt/ottochain/jars/cl-keytool.jar generate

# Create data dir
mkdir -p /opt/ottochain/data/cl1-4

# Start new node
docker run -d --name cl1-3 --restart unless-stopped --network host \
  -v /opt/ottochain/jars:/jars:ro \
  -v /opt/ottochain/keys5:/keys:ro \
  -v /opt/ottochain/data/cl1-4:/data -w /data \
  -e CL_KEYSTORE=/keys/key.p12 -e CL_KEYALIAS=alias -e CL_PASSWORD=password \
  -e CL_L0_TOKEN_IDENTIFIER=$TOKEN \
  -e CL_GLOBAL_L0_PEER_ID=$GL0_PEER_ID -e CL_GLOBAL_L0_PEER_HTTP_HOST=$HOST -e CL_GLOBAL_L0_PEER_HTTP_PORT=9000 \
  -e CL_L0_PEER_ID=$GL0_PEER_ID -e CL_L0_PEER_HTTP_HOST=$HOST -e CL_L0_PEER_HTTP_PORT=9200 \
  -e CL_EXTERNAL_IP=$HOST \
  -e CL_PUBLIC_HTTP_PORT=9330 -e CL_P2P_HTTP_PORT=9331 -e CL_CLI_HTTP_PORT=9332 \
  -e CL_APP_ENV=dev -e CL_COLLATERAL=0 \
  eclipse-temurin:21-jre-jammy java -Xmx1g -jar /jars/currency-l1.jar run-validator

# Join to existing cluster
sleep 10
curl -X POST "http://localhost:9332/cluster/join" -H "Content-Type: application/json" \
  -d '{"id": "'$GL0_PEER_ID'", "ip": "'$HOST'", "p2pPort": 9301}'
```

### Add Node on Different Machine

```bash
# On new machine
# 1. Copy JARs and generate unique key
# 2. Use the SAME GL0_PEER_ID and TOKEN
# 3. Point CL_GLOBAL_L0_PEER_HTTP_HOST to the GL0 machine IP
# 4. Point CL_L0_PEER_HTTP_HOST to the ML0 machine IP
# 5. Set CL_EXTERNAL_IP to THIS machine's IP
# 6. Run as run-validator (not run-initial-validator)
# 7. Join to existing cluster node
```

---

## Removing/Replacing Nodes

### Graceful Node Removal

```bash
# Stop the node
docker stop cl1-2
docker rm cl1-2

# Cluster will continue with remaining nodes (need at least 3 for consensus)
# Remaining nodes will detect peer is gone
```

### Replace a Node's Key

```bash
# 1. Stop the node
docker stop cl1-2
docker rm cl1-2

# 2. Generate new key
rm -rf /opt/ottochain/keys3/*
cd /opt/ottochain/keys3
CL_KEYSTORE=key.p12 CL_KEYALIAS=alias CL_PASSWORD=password \
  java -jar /opt/ottochain/jars/cl-keytool.jar generate

# 3. Clear data (to avoid peer ID conflicts)
rm -rf /opt/ottochain/data/cl1-3/*

# 4. Restart node
docker run -d --name cl1-2 ... run-validator

# 5. Rejoin cluster
curl -X POST "http://localhost:9322/cluster/join" ...
```

---

## Resetting Genesis

**WARNING: This destroys all state!**

```bash
# Stop everything
docker stop gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2
docker rm gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2

# Clear all data
rm -rf /opt/ottochain/data/*
mkdir -p /opt/ottochain/data/{gl0,ml0,cl1,cl1-2,cl1-3,dl1,dl1-2,dl1-3}

# Optionally regenerate keys
# rm -rf /opt/ottochain/keys*
# mkdir -p /opt/ottochain/keys{1,2,3,4}
# ... regenerate ...

# Restart from scratch
./deploy/scripts/setup-metagraph.sh
```

---

## Monitoring

### Health Check Script

```bash
#!/bin/bash
# save as /opt/ottochain/check-health.sh

echo "=== OttoChain Health Check ==="
echo "Time: $(date)"
echo ""

echo "GL0: $(curl -s http://localhost:9000/node/info | jq -r '.state // "DOWN"')"
echo "ML0: $(curl -s http://localhost:9200/node/info | jq -r '.state // "DOWN"')"

CL1_COUNT=$(curl -s http://localhost:9300/cluster/info 2>/dev/null | jq 'length // 0')
DL1_COUNT=$(curl -s http://localhost:9400/cluster/info 2>/dev/null | jq 'length // 0')
echo "CL1: $CL1_COUNT nodes"
echo "DL1: $DL1_COUNT nodes"

ML0_ORDINAL=$(curl -s http://localhost:9200/data-application/v1/checkpoint | jq -r '.ordinal // "N/A"')
echo "ML0 Ordinal: $ML0_ORDINAL"

echo ""
echo "Container Status:"
docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "gl0|ml0|cl1|dl1"
```

### Cron for Monitoring

```bash
# Add to crontab
*/5 * * * * /opt/ottochain/check-health.sh >> /var/log/ottochain-health.log 2>&1
```

### Log Aggregation

```bash
# View all metagraph logs
docker logs -f gl0 &
docker logs -f ml0 &
docker logs -f cl1 &
docker logs -f dl1 &

# Or use a combined log viewer
docker logs --tail 100 -f gl0 2>&1 | sed 's/^/[GL0] /' &
docker logs --tail 100 -f ml0 2>&1 | sed 's/^/[ML0] /' &
```

---

## Backup & Recovery

### Backup State

```bash
# Stop nodes first for consistent backup
docker stop ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2

# Backup data directories
tar -czvf ottochain-backup-$(date +%Y%m%d).tar.gz /opt/ottochain/data/

# Backup keys (store securely!)
tar -czvf ottochain-keys-$(date +%Y%m%d).tar.gz /opt/ottochain/keys*/

# Restart nodes
docker start ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2
```

### Restore from Backup

```bash
# Stop everything
docker stop gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2

# Restore data
rm -rf /opt/ottochain/data/*
tar -xzvf ottochain-backup-YYYYMMDD.tar.gz -C /

# Restart
docker start gl0 ml0 cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2
```

---

## Scaling

### Horizontal Scaling (Multiple Machines)

**Setup:**
- Machine A: GL0, ML0
- Machine B: CL1×3
- Machine C: DL1×3

**Key Changes:**
1. All machines need the same `GL0_PEER_ID` and `TOKEN`
2. Each machine uses its own IP for `CL_EXTERNAL_IP`
3. Point `CL_GLOBAL_L0_PEER_HTTP_HOST` to Machine A
4. Point `CL_L0_PEER_HTTP_HOST` to Machine A
5. For cluster joins, use actual IPs not localhost

### Vertical Scaling

```bash
# Increase memory for heavy nodes
docker stop ml0
docker rm ml0
docker run -d --name ml0 ... java -Xmx4g -jar /jars/metagraph-l0.jar ...
```

---

## Upgrading Tessellation

**CRITICAL: Version must match across all components!**

### Check Current Version

```bash
curl -s http://localhost:9200/node/info | jq .version
```

### Upgrade Process

1. **Check Compatibility**
   - OttoChain metakit version must be compatible with new tessellation
   - Check release notes for breaking changes

2. **Update OttoChain Dependencies**
   ```scala
   // In Dependencies.scala
   val tessellation = "4.x.x"  // New version
   ```

3. **Rebuild JARs**
   ```bash
   sbt clean assembly
   ```

4. **Download New Tessellation Tools**
   ```bash
   NEW_VERSION="v4.x.x"
   curl -L -o cl-keytool.jar "https://github.com/Constellation-Labs/tessellation/releases/download/${NEW_VERSION}/cl-keytool.jar"
   curl -L -o cl-wallet.jar "https://github.com/Constellation-Labs/tessellation/releases/download/${NEW_VERSION}/cl-wallet.jar"
   ```

5. **Full Restart with New JARs**
   - See "Full Restart" section above

---

## Services Stack Updates

### Update Services Code

```bash
cd /opt/ottochain-services
git pull origin main
pnpm install
pnpm build

# Rebuild and restart containers
docker compose build
docker compose up -d
```

### Database Migrations

```bash
docker exec ottochain-services-indexer-1 npx prisma migrate deploy
# Or for dev:
docker exec ottochain-services-indexer-1 npx prisma db push
```

### Update Explorer

```bash
cd /opt/ottochain-explorer
git pull origin main
pnpm install
pnpm build
cp -r dist/* /opt/ottochain-explorer/dist/
# Nginx will serve new files automatically
```

---

## Troubleshooting Evolution Issues

### Node Won't Rejoin After Update

```bash
# Clear node's data and rejoin fresh
docker stop cl1-2
docker rm cl1-2
rm -rf /opt/ottochain/data/cl1-3/*
# Restart and rejoin
```

### Version Mismatch Errors

```bash
# Check all node versions
for p in 9000 9200 9300 9310 9320 9400 9410 9420; do
  echo -n "Port $p: "
  curl -s http://localhost:$p/node/info | jq -r .version
done
```

### State Divergence

If nodes have diverged state:
1. Stop all L1 nodes
2. Clear L1 data directories
3. Restart L1 nodes (they'll sync from L0)

```bash
docker stop cl1 cl1-1 cl1-2 dl1 dl1-1 dl1-2
rm -rf /opt/ottochain/data/cl1* /opt/ottochain/data/dl1*
mkdir -p /opt/ottochain/data/{cl1,cl1-2,cl1-3,dl1,dl1-2,dl1-3}
# Restart and rejoin clusters
```
