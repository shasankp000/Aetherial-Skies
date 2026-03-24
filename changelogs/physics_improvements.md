# Aetherial Skies — Physics Feel Improvements
## Goal
Make `GravityBlockEntity` feel like a true rigid body rather than a gravel/sand block.
The three changes below are targeted and self-contained — they do not touch the sleep/settle system.

---

## Change 1 — Freefall tumble driven by horizontal velocity

**File:** `src/main/java/net/shasankp000/Entity/GravityBlockEntity.java`
**Method:** `tick()`
**Where:** After the gravity acceleration line in the non-fluid branch:
```java
velocity = velocity.add(0.0D, -profile.gravityAccel(), 0.0D);
```

**Add immediately after:**
```java
// Drive angular velocity from horizontal motion during freefall.
// Heavier blocks (higher mass) tumble less than lighter ones.
double horizontalSpeed = velocity.horizontalLength();
if (!this.isOnGround() && landingTimer == 0) {
    float tumbleFactor = MathHelper.clamp(
        (float)(horizontalSpeed * 2.5f) / mass,
        0.0f,
        2.5f
    );
    angularVelocity += tumbleFactor;
    // Add a randomised pitch component so tumble isn't purely yaw.
    pitchAngularVelocity += tumbleFactor * 0.4f * (this.random.nextBoolean() ? 1f : -1f);
}
```

**Why:** Currently angular velocity is only injected on impact. This drives continuous tumbling during freefall proportional to horizontal speed, making the block look like it's actually moving through air rather than floating straight down.

---

## Change 2 — Spawn-time horizontal nudge

**File:** `src/main/java/net/shasankp000/AetherialSkies.java`
**Method:** `onInitialize()` inside the `ServerTickEvents.END_SERVER_TICK` lambda
**Where:** After `gravityBlockEntity.refreshPositionAndAngles(...)` and before `spawnEntity(...)`

**Add:**
```java
// Give the block a tiny random horizontal velocity on spawn.
// This seeds the freefall tumble system immediately rather than waiting
// for a collision to inject angular momentum.
gravityBlockEntity.setVelocity(
    (server.getOverworld().getRandom().nextFloat() - 0.5f) * 0.08f,
    0.0,
    (server.getOverworld().getRandom().nextFloat() - 0.5f) * 0.08f
);
```

**Why:** Without any horizontal velocity on spawn, blocks fall perfectly vertical and the freefall tumble in Change 1 never activates. This small nudge (max 0.04 blocks/tick in any direction) is subtle enough to look natural but enough to seed the tumble system. The nudge magnitude is intentionally small — do not increase it significantly or blocks will visibly drift sideways on placement.

---

## Change 3 — Material-dependent ground sliding

**File:** `src/main/java/net/shasankp000/Entity/GravityBlockEntity.java`
**Method:** `applyGroundFriction(Vec3d velocity, GravityData.PhysicsProfile profile, float mass)`
**Where:** Replace the existing `retention` line:

**Current code:**
```java
double retention = MathHelper.clamp(profile.groundFriction(), 0.40f, 0.90f);
```

**Replace with:**
```java
// Invert friction feel: high groundFriction blocks (stone, cobblestone) stop quickly,
// low groundFriction blocks (oak planks, bookshelf) slide noticeably before settling.
double retention = MathHelper.clamp(1.0f - (profile.groundFriction() * 0.55f), 0.35f, 0.72f);
```

**Why:** The original retention directly used `groundFriction` values (0.58–0.72), which made all blocks kill horizontal momentum at roughly the same rate. Inverting the relationship means:
- Stone (`groundFriction = 0.72`) → `retention ≈ 0.60` — stops relatively quickly
- Oak planks (`groundFriction = 0.58`) → `retention ≈ 0.68` — slides noticeably further
- This creates a visible material difference in post-impact sliding behaviour.

---

## Testing checklist after implementation

- [ ] Place a gravity block and observe freefall — it should tumble visibly rather than fall straight
- [ ] Heavier blocks (stone, cobblestone) should tumble slower than lighter ones (oak planks, bookshelf)
- [ ] On landing, oak planks should slide further than stone before settling
- [ ] The sleep/settle system should still kick in cleanly — no infinite sliding or jitter
- [ ] Blocks placed above water should still float/bob correctly — wave forces should be unaffected
- [ ] Mining progression and crack textures should be unaffected

---

## Notes for Copilot/Codex/Any other AI Agent

- All three changes are additive — no existing logic needs to be deleted except the single `retention` line in Change 3
- The `MathHelper.clamp` import is already present throughout the file
- `this.random` is available via the `Entity` base class — no new imports needed
- `landingTimer` is an existing field — no new fields needed
- If tumble feels too aggressive in testing, reduce the `2.5f` multiplier in Change 1 first
- If the spawn nudge feels too visible, reduce `0.08f` in Change 2 — do not go above `0.12f`
- If sliding feels too long, increase the `0.55f` coefficient in Change 3 toward `0.65f`
