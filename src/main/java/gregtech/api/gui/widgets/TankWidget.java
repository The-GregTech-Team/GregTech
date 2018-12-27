package gregtech.api.gui.widgets;

import gregtech.api.gui.Widget;
import gregtech.api.gui.resources.RenderUtil;
import gregtech.api.gui.resources.TextureArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fluids.*;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TankWidget extends Widget {

    public final IFluidTank fluidTank;

    public final int x, y, width, height;
    public int fluidRenderOffset = 1;

    private boolean hideTooltip;
    private boolean alwaysShowFull;

    private boolean allowClickFilling;
    private boolean allowClickEmptying;

    private TextureArea[] backgroundTexture;
    private TextureArea overlayTexture;

    private FluidStack lastFluidInTank;
    private int lastTankCapacity;

    public TankWidget(IFluidTank fluidTank, int x, int y, int width, int height) {
        super(SLOT_DRAW_PRIORITY + 100);
        this.fluidTank = fluidTank;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public TankWidget setHideTooltip(boolean hideTooltip) {
        this.hideTooltip = hideTooltip;
        return this;
    }

    public TankWidget setAlwaysShowFull(boolean alwaysShowFull) {
        this.alwaysShowFull = alwaysShowFull;
        return this;
    }

    public TankWidget setBackgroundTexture(TextureArea... backgroundTexture) {
        this.backgroundTexture = backgroundTexture;
        return this;
    }

    public TankWidget setOverlayTexture(TextureArea overlayTexture) {
        this.overlayTexture = overlayTexture;
        return this;
    }

    public TankWidget setFluidRenderOffset(int fluidRenderOffset) {
        this.fluidRenderOffset = fluidRenderOffset;
        return this;
    }

    public TankWidget setContainerIO(boolean allowClickContainerFilling, boolean allowClickContainerEmptying) {
        if(!(fluidTank instanceof IFluidHandler))
            throw new IllegalStateException("Container IO is only supported for fluid tanks that implement IFluidHandler");
        this.allowClickFilling = allowClickContainerFilling;
        this.allowClickEmptying = allowClickContainerEmptying;
        return this;
    }

    public FluidStack getLastFluidInTank() {
        return lastFluidInTank;
    }

    public String getFormattedFluidAmount() {
        return String.format("%,d", lastFluidInTank == null ? 0 : lastFluidInTank.amount);
    }

    public String getFluidLocalizedName() {
        return lastFluidInTank == null ? "" : lastFluidInTank.getLocalizedName();
    }

    @Override
    public void drawInBackground(int mouseX, int mouseY) {
        if(backgroundTexture != null) {
            for(TextureArea textureArea : backgroundTexture) {
                textureArea.draw(x, y, width, height);
            }
        }
        //do not draw fluids if they are handled by JEI - it draws them itself
        if(lastFluidInTank != null && lastFluidInTank.amount > 0 && !gui.isJEIHandled) {
            GlStateManager.disableBlend();
            RenderUtil.drawFluidForGui(lastFluidInTank, alwaysShowFull ? lastFluidInTank.amount : lastTankCapacity,
                x + fluidRenderOffset, y + fluidRenderOffset,
                width - fluidRenderOffset, height - fluidRenderOffset);
            int bucketsAmount = lastFluidInTank.amount / 1000;
            if(alwaysShowFull && !hideTooltip && bucketsAmount > 0) {
                String s = String.valueOf(bucketsAmount);
                FontRenderer fontRenderer = Minecraft.getMinecraft().fontRenderer;
                fontRenderer.drawStringWithShadow(s, x + 1 + width - 2 - fontRenderer.getStringWidth(s), y + (height / 3) + 3, 0xFFFFFF);
            }
            GlStateManager.enableBlend();
        }
        if(overlayTexture != null) {
            overlayTexture.draw(x, y, width, height);
        }

    }

    @Override
    public void drawInForeground(int mouseX, int mouseY) {
        if(!hideTooltip && !gui.isJEIHandled && isMouseOver(x, y, width, height, mouseX, mouseY)) {
            List<String> tooltips = new ArrayList<>();
            if(lastFluidInTank != null) {
                Fluid fluid = lastFluidInTank.getFluid();
                tooltips.add(fluid.getLocalizedName(lastFluidInTank));
                tooltips.add(I18n.format("gregtech.fluid.amount", lastFluidInTank.amount, lastTankCapacity));
                tooltips.add(I18n.format("gregtech.fluid.temperature", fluid.getTemperature(lastFluidInTank)));
                tooltips.add(I18n.format(fluid.isGaseous(lastFluidInTank) ? "gregtech.fluid.state_gas" : "gregtech.fluid.state_liquid"));
            } else {
                tooltips.add(I18n.format("gregtech.fluid.empty"));
                tooltips.add(I18n.format("gregtech.fluid.amount", 0, lastTankCapacity));
            }
            if(allowClickFilling) {
                tooltips.add(""); //add empty line to separate things
                tooltips.add(I18n.format("gregtech.fluid.click_to_fill"));
                tooltips.add(I18n.format("gregtech.fluid.click_to_fill.shift"));
            }
            if(allowClickEmptying) {
                tooltips.add(""); //add empty line to separate things
                tooltips.add(I18n.format("gregtech.fluid.click_to_empty"));
                tooltips.add(I18n.format("gregtech.fluid.click_to_empty.shift"));
            }
            GuiUtils.drawHoveringText(tooltips, mouseX, mouseY, gui.width, gui.height, -1, Minecraft.getMinecraft().fontRenderer);
            GlStateManager.color(1.0f, 1.0f, 1.0f);
        }
    }

    @Override
    public void detectAndSendChanges() {
        FluidStack fluidStack = fluidTank.getFluid();
        if(fluidTank.getCapacity() != lastTankCapacity) {
            this.lastTankCapacity = fluidTank.getCapacity();
            writeUpdateInfo(0, buffer -> buffer.writeInt(lastTankCapacity));
        }
        if(fluidStack == null && lastFluidInTank != null) {
            this.lastFluidInTank = null;
            writeUpdateInfo(1, buffer -> {});
        } else if(fluidStack != null) {
            if(!fluidStack.isFluidEqual(lastFluidInTank)) {
                this.lastFluidInTank = fluidStack.copy();
                NBTTagCompound fluidStackTag = fluidStack.writeToNBT(new NBTTagCompound());
                writeUpdateInfo(2, buffer -> buffer.writeCompoundTag(fluidStackTag));
            } else if(fluidStack.amount != lastFluidInTank.amount) {
                this.lastFluidInTank.amount = fluidStack.amount;
                writeUpdateInfo(3, buffer -> buffer.writeInt(lastFluidInTank.amount));
            }
        }
    }

    @Override
    public void readUpdateInfo(int id, PacketBuffer buffer) {
        if(id == 0) {
            this.lastTankCapacity = buffer.readInt();
        } else if(id == 1) {
            this.lastFluidInTank = null;
        } else if(id == 2) {
            NBTTagCompound fluidStackTag;
            try {
                fluidStackTag = buffer.readCompoundTag();
            } catch (IOException ignored) {
                return;
            }
            this.lastFluidInTank = FluidStack.loadFluidStackFromNBT(fluidStackTag);
        } else if(id == 3 && lastFluidInTank != null) {
            this.lastFluidInTank.amount = buffer.readInt();
        }

        if(id == 4) {
            ItemStack currentStack = gui.entityPlayer.inventory.getItemStack();
            int newStackSize = buffer.readInt();
            currentStack.setCount(newStackSize);
            gui.entityPlayer.inventory.setItemStack(currentStack);
        }
    }

    @Override
    public void handleClientAction(int id, PacketBuffer buffer) {
        super.handleClientAction(id, buffer);
        if(id == 1) {
            boolean isRightClick = buffer.readBoolean();
            boolean isShiftKeyDown = buffer.readBoolean();
            int clickResult = tryClickContainer(isRightClick, isShiftKeyDown);
            if(clickResult >= 0) {
                writeUpdateInfo(4, buf -> buf.writeInt(clickResult));
            }
        }
    }

    private int tryClickContainer(boolean isRightClick, boolean isShiftKeyDown) {
        EntityPlayer player = gui.entityPlayer;
        ItemStack currentStack = player.inventory.getItemStack();
        if(!currentStack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null))
            return -1;
        int maxAttempts = isShiftKeyDown ? currentStack.getCount() : 1;

        if(allowClickFilling && fluidTank.getFluidAmount() > 0 && !isRightClick) {
            boolean performedFill = false;
            FluidStack initialFluid = fluidTank.getFluid();
            for(int i = 0; i < maxAttempts; i++) {
                FluidActionResult result = FluidUtil.tryFillContainer(currentStack,
                    (IFluidHandler) fluidTank, Integer.MAX_VALUE, null, false);
                if(!result.isSuccess()) break;
                ItemStack remainingStack = result.getResult();
                if(!remainingStack.isEmpty() && !player.inventory.addItemStackToInventory(remainingStack))
                    break; //do not continue if we can't add resulting container into inventory
                FluidUtil.tryFillContainer(currentStack, (IFluidHandler) fluidTank, Integer.MAX_VALUE, null, true);
                currentStack.shrink(1);
                performedFill = true;
            }
            if(performedFill) {
                SoundEvent soundevent = initialFluid.getFluid().getFillSound(initialFluid);
                player.world.playSound(null, player.posX, player.posY + 0.5, player.posZ,
                    soundevent, SoundCategory.BLOCKS, 1.0F, 1.0F);
                gui.entityPlayer.inventory.setItemStack(currentStack);
                return currentStack.getCount();
            }
        }

        if(allowClickEmptying && isRightClick) {
            boolean performedEmptying = false;
            for(int i = 0; i < maxAttempts; i++) {
                FluidActionResult result = FluidUtil.tryEmptyContainer(currentStack,
                    (IFluidHandler) fluidTank, Integer.MAX_VALUE, null, false);
                if(!result.isSuccess()) break;
                ItemStack remainingStack = result.getResult();
                if(!remainingStack.isEmpty() && !player.inventory.addItemStackToInventory(remainingStack))
                    break; //do not continue if we can't add resulting container into inventory
                FluidUtil.tryEmptyContainer(currentStack, (IFluidHandler) fluidTank, Integer.MAX_VALUE, null, true);
                currentStack.shrink(1);
                performedEmptying = true;
            }
            FluidStack filledFluid = fluidTank.getFluid();
            if(performedEmptying) {
                SoundEvent soundevent = filledFluid.getFluid().getEmptySound(filledFluid);
                player.world.playSound(null, player.posX, player.posY + 0.5, player.posZ,
                    soundevent, SoundCategory.BLOCKS, 1.0F, 1.0F);
                gui.entityPlayer.inventory.setItemStack(currentStack);
                return currentStack.getCount();
            }
        }

        return -1;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if(!isMouseOver(x, y, width, height, mouseX, mouseY))
            return;
        ItemStack currentStack = gui.entityPlayer.inventory.getItemStack();
        if((allowClickEmptying || allowClickFilling) &&
            currentStack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null)) {
            boolean isShiftKeyDown = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            writeClientAction(1, writer -> writer.writeBoolean(button == 0).writeBoolean(isShiftKeyDown));
        }
    }
}
