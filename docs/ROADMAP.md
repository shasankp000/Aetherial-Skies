# Aetherial Skies — Development Roadmap
> Goal: a safe, bug-free alternative to ValkyrienSkies 2 (VS2) on Fabric 1.20.1  
> Last updated: 2026-03-29

---

## Current State (as of 2026-03-29)

### ✅ Done / Working
- Jolt Physics (`jolt-jni 3.9.0`) integrated via `JoltPhysicsSystem` singleton
- `JoltPhysicsSystem.init()` called on `SERVER_STARTED`; `destroy()` on `SERVER_STOPPING`
- `ShipTransformManager.tick()` called every `END_SERVER_TICK`
- `registerShipBody()` called from `ShipTransformManager.onShipDeployed()` — Jolt kinematic body IS registered when a ship is deployed
- `removeShipBody()` called from `onShipDestroyed()` — body cleaned up correctly
- Per-tick loop: `engine.tick()` → `updateBodyTransform()` → `stepSimulation()` → `readBackFromJolt()` — **fully wired**
- `ShipPassengerTracker` exists and is called each tick
- Client-side transform sync packet (`ShipTransformSyncS2CPacket`) broadcast each tick to all players
- Game launches clean, world loads, no crash

### ⚠️ Known Issues / Not Yet Verified
- `stepSimulation()` is called **twice** per tick (once inside `ShipTransformManager.tick()`, once again in `END_SERVER_TICK` directly in `AetherialSkies.java`) — **double-step bug, needs fixing**
- Player riding: `ShipPassengerTracker` exists but correctness of delta-transform application is unverified in-game
- Block rendering during flight: client receives sync packets but visual movement fidelity is unconfirmed
- No persistence across server restart (hull state, body transform not serialized to NBT)
- Collision response: body is kinematic — ship will phase through terrain, no response yet

---

## Phase 1 — Foundation Bulletproof (Immediate Priority)

| # | Task | Status |
|---|------|--------|
| 1.1 | **Fix double `stepSimulation()` call** — remove the redundant call in `AetherialSkies.java`; `ShipTransformManager.tick()` already steps Jolt | 🔴 TODO |
| 1.2 | **Verify player riding in-game** — board a moving ship, confirm player moves with it and doesn't fall through | 🔴 TODO |
| 1.3 | **Verify block rendering during flight** — visually confirm ship blocks move smoothly on client | 🔴 TODO |
| 1.4 | **Chunk/ghost block audit** — confirm ship blocks don't cause lighting updates or phantom collisions in the world chunk | 🔴 TODO |

---

## Phase 2 — Core Ship Mechanics (Short-term)

| # | Task | Status |
|---|------|--------|
| 2.1 | **Persistence across restarts** — serialize `ShipHullData` + world offset + yaw to NBT via `PersistentState` | 🔴 TODO |
| 2.2 | **Multi-player sync correctness** — verify all nearby players (not just pilot) receive smooth transform updates | 🔴 TODO |
| 2.3 | **Docking / landing flow** — freeze Jolt body, snap blocks back to world grid, clean body removal | 🔴 TODO |
| 2.4 | **Collision detection (kinematic)** — detect Jolt contact events and cancel ship movement on terrain impact instead of phasing through | 🔴 TODO |
| 2.5 | **Reduce per-tick GC pressure** — cache `RVec3`/`Quat` objects in `updateBodyTransform()` instead of allocating new ones every tick | 🔴 TODO |

---

## Phase 3 — VS2 Parity Features (Medium-term)

| # | Feature | Notes |
|---|---------|-------|
| 3.1 | **Thruster / engine blocks** | Apply impulse forces to the Jolt body each tick based on active thruster blocks |
| 3.2 | **Entity mounting (mobs on ship)** | Apply same delta-transform as players to all entities in ship AABB |
| 3.3 | **Weight / mass simulation** | Switch from kinematic → dynamic body; derive mass from block count × density |
| 3.4 | **Water buoyancy** | Use Jolt's `applyBuoyancyImpulse()` — API already present in jolt-jni |
| 3.5 | **Ship-to-ship interaction** | Multiple Jolt bodies + Jolt constraints between them |
| 3.6 | **Cannon / projectile blocks** | Spawn a Jolt dynamic sphere body as projectile, apply recoil impulse to ship |

---

## Phase 4 — Stability & Safety (Ongoing)

| # | Task |
|---|------|
| 4.1 | **Thread-safety audit** — assert server thread for all `JoltPhysicsSystem` calls in debug builds |
| 4.2 | **Body limit graceful degradation** — surface a player-facing message when `createBody` returns null |
| 4.3 | **Integration test suite** — headless JUnit tests: spin up `JoltPhysicsSystem`, register body, step 20 ticks, assert position |
| 4.4 | **Memory leak audit** — profile heap allocations per tick under load with multiple ships active |
| 4.5 | **Crash reporter** — catch and log all unchecked exceptions in `ShipTransformManager.tick()` so one broken ship doesn't kill the server tick |

---

## Immediate Next Action

**Fix the double `stepSimulation()` bug first** (Phase 1.1).  
The call in `AetherialSkies.java` `END_SERVER_TICK` is redundant — `ShipTransformManager.tick()` already calls it internally. This means Jolt advances the simulation twice per Minecraft tick, causing ships to move at double speed and physics to be incorrect.

Then proceed with in-game verification of player riding (Phase 1.2).
