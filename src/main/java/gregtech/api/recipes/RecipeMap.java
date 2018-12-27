package gregtech.api.recipes;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.liquid.ILiquidStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import gnu.trove.map.TByteObjectMap;
import gnu.trove.map.hash.TByteObjectHashMap;
import gregtech.api.GTValues;
import gregtech.api.capability.IMultipleTankHandler;
import gregtech.api.capability.impl.FluidTankList;
import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.resources.TextureArea;
import gregtech.api.gui.widgets.ProgressWidget;
import gregtech.api.gui.widgets.ProgressWidget.MoveType;
import gregtech.api.gui.widgets.SlotWidget;
import gregtech.api.gui.widgets.TankWidget;
import gregtech.api.recipes.builders.IntCircuitRecipeBuilder;
import gregtech.api.recipes.crafttweaker.CTRecipe;
import gregtech.api.recipes.crafttweaker.CTRecipeBuilder;
import gregtech.api.unification.material.type.Material;
import gregtech.api.unification.ore.OrePrefix;
import gregtech.api.util.GTLog;
import gregtech.api.util.GTUtility;
import gregtech.api.util.ValidationResult;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Optional.Method;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandlerModifiable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.DoubleSupplier;
import java.util.stream.Collectors;

@ZenClass("mods.gregtech.recipe.RecipeMap")
@ZenRegister
public class RecipeMap<R extends RecipeBuilder<R>> {

	private static final List<RecipeMap<?>> RECIPE_MAPS = new ArrayList<>();

	public final String unlocalizedName;

	private final R recipeBuilderSample;
	private final int minInputs, maxInputs;
	private final int minOutputs, maxOutputs;
	private final int minFluidInputs, maxFluidInputs;
	private final int minFluidOutputs, maxFluidOutputs;
	private final int amperage;
	private final TByteObjectMap<TextureArea> slotOverlays;
	private TextureArea progressBarTexture;
	private MoveType moveType;

    private final Map<FluidKey, Collection<Recipe>> recipeFluidMap = new HashMap<>();
    private final Collection<Recipe> recipeList = new ArrayList<>();

	public RecipeMap(String unlocalizedName,
                     int minInputs, int maxInputs, int minOutputs, int maxOutputs,
                     int minFluidInputs, int maxFluidInputs, int minFluidOutputs, int maxFluidOutputs,
                     int amperage, R defaultRecipe) {
        this.unlocalizedName = unlocalizedName;
		this.amperage = amperage;
		this.slotOverlays = new TByteObjectHashMap<>();
		this.progressBarTexture = GuiTextures.PROGRESS_BAR_ARROW;
		this.moveType = MoveType.HORIZONTAL;

		this.minInputs = minInputs;
		this.minFluidInputs = minFluidInputs;
		this.minOutputs = minOutputs;
		this.minFluidOutputs = minFluidOutputs;

		this.maxInputs = maxInputs;
		this.maxFluidInputs = maxFluidInputs;
		this.maxOutputs = maxOutputs;
		this.maxFluidOutputs = maxFluidOutputs;

        defaultRecipe.setRecipeMap(this);
        this.recipeBuilderSample = defaultRecipe;
        RECIPE_MAPS.add(this);
	}

	@ZenMethod
	public static List<RecipeMap<?>> getRecipeMaps() {
	    return Collections.unmodifiableList(RECIPE_MAPS);
    }

    @ZenMethod
    public static RecipeMap<?> getByName(String unlocalizedName) {
	    return RECIPE_MAPS.stream()
            .filter(map -> map.unlocalizedName.equals(unlocalizedName))
            .findFirst().orElse(null);
    }

    public static boolean isFoundInvalidRecipe() {
        return foundInvalidRecipe;
    }

    public static void setFoundInvalidRecipe(boolean foundInvalidRecipe) {
        RecipeMap.foundInvalidRecipe |= foundInvalidRecipe;
        OrePrefix currentOrePrefix = OrePrefix.getCurrentProcessingPrefix();
        if(currentOrePrefix != null) {
            Material currentMaterial = OrePrefix.getCurrentMaterial();
            GTLog.logger.error("Error happened during processing ore registration of prefix {} and material {}. " +
                    "Seems like cross-mod compatibility issue. Report to GTCE github.",
                currentOrePrefix, currentMaterial);
        }
    }

    public RecipeMap<R> setProgressBar(TextureArea progressBar, MoveType moveType) {
        this.progressBarTexture = progressBar;
        this.moveType = moveType;
        return this;
    }

    public RecipeMap<R> setSlotOverlay(boolean isOutput, boolean isFluid, TextureArea slotOverlay) {
	    return this
            .setSlotOverlay(isOutput, isFluid, false, slotOverlay)
            .setSlotOverlay(isOutput, isFluid, true, slotOverlay);
    }

    public RecipeMap<R> setSlotOverlay(boolean isOutput, boolean isFluid, boolean isLast, TextureArea slotOverlay) {
	    this.slotOverlays.put((byte) ((isOutput ? 2 : 0) + (isFluid ? 1 : 0) + (isLast ? 4 : 0)), slotOverlay);
	    return this;
    }

    /**
     * This is alternative case when machine can input given fluid
     * If this method returns true, machine will receive given fluid even if getRecipesForFluid doesn't have
     * any recipe for this fluid
     */
    public boolean canInputFluidForce(Fluid fluid) {
	    return false;
    }

    public Collection<Recipe> getRecipesForFluid(FluidStack fluid) {
        return recipeFluidMap.getOrDefault(new FluidKey(fluid), Collections.emptySet());
    }

    private static boolean foundInvalidRecipe = false;

	//internal usage only, use buildAndRegister()
	public void addRecipe(ValidationResult<Recipe> validationResult) {
		switch (validationResult.getType()) {
			case SKIP:
				return;
			case INVALID:
				setFoundInvalidRecipe(true);
				return;
		}
		Recipe recipe = validationResult.getResult();
		recipeList.add(recipe);

		for (FluidStack fluid : recipe.getFluidInputs()) {
			recipeFluidMap.computeIfAbsent(new FluidKey(fluid), k -> new HashSet<>(1)).add(recipe);
		}
	}

	public boolean removeRecipe(Recipe recipe) {
	    //if we actually removed this recipe
	    if(recipeList.remove(recipe)) {
	        //also iterate trough fluid mappings and remove recipe from them
	        recipeFluidMap.values().forEach(fluidMap ->
                fluidMap.removeIf(fluidRecipe -> fluidRecipe == recipe));
	        return true;
        }
        return false;
    }

    @Nullable
    public Recipe findRecipe(long voltage, IItemHandlerModifiable inputs, IMultipleTankHandler fluidInputs) {
        return this.findRecipe(voltage, GTUtility.itemHandlerToList(inputs), GTUtility.fluidHandlerToList(fluidInputs));
    }

	/**
	 * Finds a Recipe matching the Fluid and ItemStack Inputs.
	 *
	 * @param voltage       Voltage of the Machine or Long.MAX_VALUE if it has no Voltage
	 * @param inputs        the Item Inputs
	 * @param fluidInputs   the Fluid Inputs
	 * @return the Recipe it has found or null for no matching Recipe
	 */
	@Nullable
	public Recipe findRecipe(long voltage, List<ItemStack> inputs, List<FluidStack> fluidInputs) {
        if (recipeList.isEmpty())
            return null;
        if (minFluidInputs > 0 && GTUtility.amountOfNonNullElements(fluidInputs) < minFluidInputs) {
            return null;
        }
        if (minInputs > 0 && GTUtility.amountOfNonEmptyStacks(inputs) < minInputs) {
            return null;
        }
        if (maxInputs > 0) {
            return findByInputs(voltage, inputs, fluidInputs);
        } else {
            return findByFluidInputs(voltage, inputs, fluidInputs);
        }
    }

    @Nullable
    private Recipe findByFluidInputs(long voltage, List<ItemStack> inputs, List<FluidStack> fluidInputs) {
        for (FluidStack fluid : fluidInputs) {
            if (fluid == null) continue;
            Collection<Recipe> recipes = recipeFluidMap.get(new FluidKey(fluid));
            if (recipes == null) continue;
            for (Recipe tmpRecipe : recipes) {
                if (tmpRecipe.matches(false, inputs, fluidInputs)) {
                    return voltage * amperage >= tmpRecipe.getEUt() ? tmpRecipe : null;
                }
            }
        }
        return null;
    }

	@Nullable
	private Recipe findByInputs(long voltage, List<ItemStack> inputs, List<FluidStack> fluidInputs) {
        for (Recipe recipe : recipeList) {
            if (recipe.matches(false, inputs, fluidInputs)) {
                return voltage * amperage >= recipe.getEUt() ? recipe : null;
            }
        }
		return null;
	}

	//this DOES NOT addFlag machine control widgets or binds player inventory
	public ModularUI.Builder createUITemplate(DoubleSupplier progressSupplier, IItemHandlerModifiable importItems, IItemHandlerModifiable exportItems, FluidTankList importFluids, FluidTankList exportFluids) {
        ModularUI.Builder builder = ModularUI.defaultBuilder();
        builder.widget(new ProgressWidget(progressSupplier, 77, 22, 20, 20, progressBarTexture, moveType));
        addInventorySlotGroup(builder, importItems, importFluids, false);
        addInventorySlotGroup(builder, exportItems, exportFluids, true);
        return builder;
    }

    private void addInventorySlotGroup(ModularUI.Builder builder, IItemHandlerModifiable itemHandler, FluidTankList fluidHandler, boolean isOutputs) {
        int itemInputsCount = itemHandler.getSlots();
        int fluidInputsCount = fluidHandler.getTanks();
        boolean invertFluids = false;
        if(itemInputsCount == 0) {
            int tmp = itemInputsCount;
            itemInputsCount = fluidInputsCount;
            fluidInputsCount = tmp;
            invertFluids = true;
        }
        int[] inputSlotGrid = determineSlotsGrid(itemInputsCount);
        int itemSlotsToLeft = inputSlotGrid[0];
        int itemSlotsToDown = inputSlotGrid[1];
        int startInputsX = isOutputs ? 106 : 69 - itemSlotsToLeft * 18;
        int startInputsY = 32 - (int) (itemSlotsToDown / 2.0 * 18);
        for(int i = 0; i < itemSlotsToDown; i++) {
            for (int j = 0; j < itemSlotsToLeft; j++) {
                int slotIndex = i * itemSlotsToLeft + j;
                addSlot(builder, startInputsX + 18 * j, startInputsY + 18 * i, slotIndex, itemHandler, fluidHandler, invertFluids, isOutputs);
            }
        }
        if(fluidInputsCount > 0 || invertFluids) {
            if(itemSlotsToDown >= fluidInputsCount) {
                int startSpecX = isOutputs ? startInputsX + itemSlotsToLeft * 18 : startInputsX - 18;
                for(int i = 0; i < fluidInputsCount; i++) {
                    addSlot(builder, startSpecX, startInputsY + 18 * i, i, itemHandler, fluidHandler, !invertFluids, isOutputs);
                }
            } else {
                int startSpecY = startInputsY + itemSlotsToDown * 18;
                int offsetX = isOutputs ? 0 : 18;
                for(int i = 0; i < fluidInputsCount; i++) {
                    addSlot(builder, startInputsX - offsetX + 18 * i, startSpecY, i, itemHandler, fluidHandler, !invertFluids, isOutputs);
                }
            }
        }
    }

    protected void addSlot(ModularUI.Builder builder, int x, int y, int slotIndex, IItemHandlerModifiable itemHandler, FluidTankList fluidHandler, boolean isFluid, boolean isOutputs) {
        if(!isFluid) {
            builder.widget(new SlotWidget(itemHandler, slotIndex, x, y, true, !isOutputs)
                .setBackgroundTexture(getOverlaysForSlot(isOutputs, false,slotIndex == itemHandler.getSlots() - 1)));
        } else {
            builder.widget(new TankWidget(fluidHandler.getTankAt(slotIndex), x - 1, y - 1, 18, 18)
                .setAlwaysShowFull(true)
                .setBackgroundTexture(getOverlaysForSlot(isOutputs, true, slotIndex == fluidHandler.getTanks() - 1))
                .setContainerIO(true, true)); // TODO Add config section for this
        }
    }

    private TextureArea[] getOverlaysForSlot(boolean isOutput, boolean isFluid, boolean isLast) {
	    TextureArea base = isFluid ? GuiTextures.FLUID_SLOT : GuiTextures.SLOT;
	    if(!isOutput && !isFluid && isLast && recipeBuilderSample instanceof IntCircuitRecipeBuilder) {
	        //automatically add int circuit overlay to last item input slot
            return new TextureArea[]{base, GuiTextures.INT_CIRCUIT_OVERLAY};
        }
        byte overlayKey = (byte) ((isOutput ? 2 : 0) + (isFluid ? 1 : 0) + (isLast ? 4 : 0));
	    if(slotOverlays.containsKey(overlayKey)) {
	        return new TextureArea[] {base, slotOverlays.get(overlayKey)};
        }
        return new TextureArea[] {base};
    }

    private static int[] determineSlotsGrid(int itemInputsCount) {
        int itemSlotsToLeft = 0;
        int itemSlotsToDown = 0;
        double sqrt = Math.sqrt(itemInputsCount);
        if (sqrt % 1 == 0) { //check if square root is integer
            //case for 1, 4, 9 slots - it's square inputs (the most common case)
            itemSlotsToLeft = itemSlotsToDown = (int) sqrt;
        } else if (itemInputsCount % 3 == 0) {
            //case for 3 and 6 slots - 3 by horizontal and i / 3 by vertical (common case too)
            itemSlotsToDown = itemInputsCount / 3;
            itemSlotsToLeft = 3;
        } else if (itemInputsCount % 2 == 0) {
            //case for 2 inputs - 2 by horizontal and i / 3 by vertical (for 2 slots)
            itemSlotsToDown = itemInputsCount / 2;
            itemSlotsToLeft = 2;
        }
        return new int[] {itemSlotsToLeft, itemSlotsToDown};
    }


    public Collection<Recipe> getRecipeList() {
        return Collections.unmodifiableCollection(recipeList);
    }

    @ZenMethod("findRecipe")
    @Method(modid = GTValues.MODID_CT)
    public CTRecipe ctFindRecipe(long maxVoltage, IItemStack[] itemInputs, ILiquidStack[] fluidInputs) {
	    List<ItemStack> mcItemInputs = itemInputs == null ? Collections.emptyList() :
            Arrays.stream(itemInputs)
            .map(CraftTweakerMC::getItemStack)
            .collect(Collectors.toList());
	    List<FluidStack> mcFluidInputs = fluidInputs == null ? Collections.emptyList() :
            Arrays.stream(fluidInputs)
            .map(CraftTweakerMC::getLiquidStack)
            .collect(Collectors.toList());
	    Recipe backingRecipe = findRecipe(maxVoltage, mcItemInputs, mcFluidInputs);
	    return backingRecipe == null ? null : new CTRecipe(this, backingRecipe);
    }

    @ZenGetter("recipes")
    @Method(modid = GTValues.MODID_CT)
    public List<CTRecipe> ccGetRecipeList() {
	    return getRecipeList().stream()
            .map(recipe -> new CTRecipe(this, recipe))
            .collect(Collectors.toList());
    }

    @SideOnly(Side.CLIENT)
    @ZenGetter("localizedName")
    public String getLocalizedName() {
	    return I18n.format("recipemap." + unlocalizedName + ".name");
    }

    @ZenGetter("unlocalizedName")
    public String getUnlocalizedName() {
	    return unlocalizedName;
    }

	public R recipeBuilder() {
		return recipeBuilderSample.copy();
	}

	@ZenMethod("recipeBuilder")
    @Method(modid = GTValues.MODID_CT)
	public CTRecipeBuilder ctRecipeBuilder() {
	    return new CTRecipeBuilder(recipeBuilder());
    }

    @ZenGetter("minInputs")
	public int getMinInputs() {
		return minInputs;
	}

    @ZenGetter("maxInputs")
	public int getMaxInputs() {
		return maxInputs;
	}

    @ZenGetter("minOutputs")
	public int getMinOutputs() {
		return minOutputs;
	}

    @ZenGetter("maxOutputs")
	public int getMaxOutputs() {
		return maxOutputs;
	}

    @ZenGetter("minFluidInputs")
	public int getMinFluidInputs() {
		return minFluidInputs;
	}

    @ZenGetter("maxFluidInputs")
	public int getMaxFluidInputs() {
		return maxFluidInputs;
	}

    @ZenGetter("minFluidOutputs")
	public int getMinFluidOutputs() {
		return minFluidOutputs;
	}

    @ZenGetter("maxFluidOutputs")
	public int getMaxFluidOutputs() {
		return maxFluidOutputs;
	}

    @ZenGetter("amperage")
    public int getAmperage() {
        return amperage;
    }

    @Override
    @ZenMethod
    public String toString() {
        return "RecipeMap{" +
            "unlocalizedName='" + unlocalizedName + '\'' +
            '}';
    }
}
