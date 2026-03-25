package net.shasankp000.Ship;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Gravity.GravityData;
import net.shasankp000.Registry.ModBlocks;
import net.shasankp000.Ship.ShipCrateService.PackedBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ShipCompileService {
    private static final Set<Block> STORAGE_BLOCKS = Set.of(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.ENDER_CHEST
    );

    private ShipCompileService() {
    }

    public static CompileResult compileSelection(ServerPlayerEntity player, ServerWorld world) {
        ShipSelectionManager.Selection selection = ShipSelectionManager.getSelection(player.getUuid()).orElse(null);
        if (selection == null) {
            return new CompileResult(false, "Select two corners with the Shipwright's Wand first.");
        }

        HelmDetection helmDetection = findHelm(world, selection);
        if (!helmDetection.found()) {
            return new CompileResult(false, "Ship compile failed: place a Ship Helm inside or directly adjacent to the selected area.");
        }

        List<CandidateBlock> candidates = new ArrayList<>();
        int totalSolidBlocks = 0;
        int storageBlocks = 0;
        for (BlockPos pos : BlockPos.iterate(selection.min(), selection.max())) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || !state.isSolidBlock(world, pos)) {
                continue;
            }

            totalSolidBlocks++;
            if (isStorageBlock(state)) {
                storageBlocks++;
                continue;
            }
            if (!state.isOf(ModBlocks.SHIPWRIGHTS_WORKBENCH)) {
                candidates.add(new CandidateBlock(pos.toImmutable(), state, false));
            }
        }

        if (helmDetection.adjacentToSelection()) {
            BlockState adjacentHelmState = world.getBlockState(helmDetection.position());
            if (adjacentHelmState.isOf(ModBlocks.SHIP_HELM)) {
                candidates.add(new CandidateBlock(helmDetection.position(), adjacentHelmState, true));
            }
        }

        if (candidates.isEmpty()) {
            return new CompileResult(false, "Ship compile failed: no convertible solid blocks found.");
        }

        PackedAssembly assembly = packIntoCrate(world, candidates, helmDetection.position());
        if (assembly == null) {
            return new CompileResult(false, "Ship compile failed during packing.");
        }

        ShipCrateService.PackResult packResult = ShipCrateService.createPackedCrate(
                player,
                assembly.blocks(),
                assembly.totalMass(),
                assembly.helmOffset(),
                assembly.helmYawDegrees(),
                assembly.hydrodynamics()
        );
        if (!packResult.success()) {
            return new CompileResult(false, packResult.message());
        }

        return new CompileResult(
                true,
                "Ship packed into crate. Solid=" + totalSolidBlocks
                        + ", packed=" + assembly.blocks().size()
                        + ", storage(placeholders)=" + storageBlocks
                        + ", mass=" + String.format("%.2f", assembly.totalMass())
        );
    }

    private static PackedAssembly packIntoCrate(ServerWorld world, List<CandidateBlock> candidates, BlockPos helmPos) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<GravityData.PhysicsProfile> hydroProfiles = new ArrayList<>();
        double weightedX = 0.0D;
        double weightedY = 0.0D;
        double weightedZ = 0.0D;
        float totalMass = 0.0f;
        List<CandidateBlock> surviving = new ArrayList<>();

        for (CandidateBlock candidate : candidates) {
            BlockState state = world.getBlockState(candidate.pos());
            if (state.isAir()) {
                continue;
            }

            float blockMass = Math.max(0.5f, GravityData.getProfile(state.getBlock()).mass());
            totalMass += blockMass;
            weightedX += (candidate.pos().getX() + 0.5D) * blockMass;
            weightedY += candidate.pos().getY() * blockMass;
            weightedZ += (candidate.pos().getZ() + 0.5D) * blockMass;
            hydroProfiles.add(GravityData.getProfile(state.getBlock()));
            surviving.add(new CandidateBlock(candidate.pos(), state, candidate.adjacentHelm()));
        }

        if (surviving.isEmpty() || totalMass <= 0.0f) {
            return null;
        }

        Vec3d center = new Vec3d(weightedX / totalMass, weightedY / totalMass, weightedZ / totalMass);
        List<PackedBlock> packedBlocks = new ArrayList<>();
        Vec3d helmOffset = Vec3d.ZERO;
        float helmYawDegrees = 0.0f;
        boolean helmFound = false;

        for (CandidateBlock candidate : surviving) {
            BlockPos pos = candidate.pos();
            BlockState state = candidate.state();
            world.removeBlock(pos, false);

            Vec3d localOffset = new Vec3d(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D).subtract(center);
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            boolean isHelm = state.isOf(ModBlocks.SHIP_HELM) && pos.equals(helmPos);
            packedBlocks.add(new PackedBlock(blockId, localOffset, isHelm));
            if (isHelm) {
                helmOffset = localOffset;
                helmYawDegrees = getHelmYawDegrees(state);
                helmFound = true;
            }
        }

        if (!helmFound) {
            for (PackedBlock block : packedBlocks) {
                if (block.isHelm()) {
                    helmOffset = block.localOffset();
                    helmFound = true;
                    break;
                }
            }
        }

        if (!helmFound) {
            return null;
        }

        GravityData.HydroComposition hydro = GravityData.composeHydrodynamics(hydroProfiles);
        return new PackedAssembly(
                packedBlocks,
                MathHelper.clamp(totalMass, 1.0f, 10000.0f),
                helmOffset,
                helmYawDegrees,
                hydro
        );
    }

    private static float getHelmYawDegrees(BlockState helmState) {
        if (!helmState.contains(Properties.HORIZONTAL_FACING)) {
            return 0.0f;
        }
        Direction facing = helmState.get(Properties.HORIZONTAL_FACING);
        return facing.asRotation();
    }

    private static HelmDetection findHelm(ServerWorld world, ShipSelectionManager.Selection selection) {
        for (BlockPos pos : BlockPos.iterate(selection.min(), selection.max())) {
            if (world.getBlockState(pos).isOf(ModBlocks.SHIP_HELM)) {
                return new HelmDetection(true, pos.toImmutable(), false);
            }
        }

        BlockPos expandedMin = selection.min().add(-1, -1, -1);
        BlockPos expandedMax = selection.max().add(1, 1, 1);
        for (BlockPos pos : BlockPos.iterate(expandedMin, expandedMax)) {
            if (isInsideSelection(pos, selection)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(ModBlocks.SHIP_HELM)) {
                return new HelmDetection(true, pos.toImmutable(), true);
            }
        }

        return new HelmDetection(false, BlockPos.ORIGIN, false);
    }

    private static boolean isInsideSelection(BlockPos pos, ShipSelectionManager.Selection selection) {
        return pos.getX() >= selection.min().getX() && pos.getX() <= selection.max().getX()
                && pos.getY() >= selection.min().getY() && pos.getY() <= selection.max().getY()
                && pos.getZ() >= selection.min().getZ() && pos.getZ() <= selection.max().getZ();
    }

    private static boolean isStorageBlock(BlockState state) {
        Block block = state.getBlock();
        return STORAGE_BLOCKS.contains(block) || block instanceof ShulkerBoxBlock;
    }

    private record CandidateBlock(BlockPos pos, BlockState state, boolean adjacentHelm) {
    }

    private record HelmDetection(boolean found, BlockPos position, boolean adjacentToSelection) {
    }

    private record PackedAssembly(List<PackedBlock> blocks, float totalMass, Vec3d helmOffset, float helmYawDegrees, GravityData.HydroComposition hydrodynamics) {
    }

    public record CompileResult(boolean success, String message) {
    }
}
