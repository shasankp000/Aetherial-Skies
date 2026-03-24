# Entity Collision Investigation (2026-03-24)

## Goal
Determine whether player slip-off behavior on floating `GravityBlockEntity` is caused by vanilla entity pushback logic (standing on an entity), not fluid drift.

## What was inspected
Using mapped Minecraft `1.21.4` bytecode from the merged Yarn jar:
- `net.minecraft.entity.Entity#pushAwayFrom(Entity)`
- `net.minecraft.entity.LivingEntity#tickCramming()`
- `net.minecraft.entity.LivingEntity#pushAway(Entity)`
- `net.minecraft.entity.LivingEntity#pushAwayFrom(Entity)`

## Vanilla findings

### 1) Vanilla push applies lateral velocity impulses
`Entity#pushAwayFrom(Entity)` applies mutual X/Z velocity impulses when entities are close:
- normalizes horizontal delta
- scales by `0.05`
- calls `addVelocity(-x, 0, -z)` on one entity and `addVelocity(x, 0, z)` on the other

So vanilla already injects sideways movement for nearby pushable entities.

### 2) Living entities run push logic every tick against nearby entities
`LivingEntity#tickCramming()` gathers nearby entities (`World.getOtherEntities(...)` with `EntityPredicates.canBePushedBy(...)`) and for each entity calls:
- `LivingEntity#pushAway(Entity)`
- which calls `entity.pushAwayFrom(this)`

This means players standing near/overlapping a pushable entity can repeatedly trigger pushback every tick.

### 3) Player uses vanilla push logic path
`LivingEntity#pushAwayFrom(Entity)` forwards to `Entity#pushAwayFrom(Entity)` (except sleeping case), confirming players participate in this push system.

## Mod-specific impact
`GravityBlockEntity` overrides `pushAwayFrom(Entity)` with stronger custom impulses and explicit player scaling, so player-on-top interactions can become more pronounced than vanilla.

Therefore, yes: the slip-off effect is primarily consistent with entity pushback behavior (vanilla path + stronger mod override), not only fluid drift.

## Practical conclusion
- Root cause is entity-entity push mechanics during close contact.
- Fluid drift can amplify motion, but pushback remains a core source even in calm water.
- To make floating blocks standable, next tuning should target collision push behavior for top-standing cases (not only wave drift).

## Suggested next session tasks
1. Add a stronger top-contact guard in `GravityBlockEntity#pushAwayFrom` so players on the upper face are exempt from lateral push.
2. Reduce or disable player-target push in `pushAwayFrom` while `smoothedPlayerLoad > 0` and player is above block top.
3. Keep non-top side collision push active so entity still feels solid from sides.
4. Re-test in calm water and ocean profiles separately.

