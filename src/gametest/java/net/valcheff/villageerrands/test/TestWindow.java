package net.valcheff.villageerrands.test;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDLVideo;

import java.nio.IntBuffer;

/**
 * Moves the test window to another monitor on the first client tick, while the game is still loading:
 * {@code ERRANDS_TEST_WINDOW="x,y"} is that monitor's top left corner in desktop coordinates.
 */
public class TestWindow implements ClientModInitializer {
	private static boolean placed;

	@Override
	public void onInitializeClient() {
		String at = System.getenv("ERRANDS_TEST_WINDOW");
		if (at == null || !at.matches("-?\\d+,-?\\d+")) return;
		String[] xy = at.split(",");
		int x = Integer.parseInt(xy[0]) + 80;
		int y = Integer.parseInt(xy[1]) + 80;
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (!placed) placed = move(mc, x, y);
		});
	}

	static boolean move(Minecraft mc, int x, int y) {
		long handle = mc.getWindow().handle();
		boolean moved = SDLVideo.SDL_SetWindowPosition(handle, x, y);
		SDLVideo.SDL_SyncWindow(handle);
		IntBuffer nx = BufferUtils.createIntBuffer(1);
		IntBuffer ny = BufferUtils.createIntBuffer(1);
		SDLVideo.SDL_GetWindowPosition(handle, nx, ny);
		System.out.println("ERRANDS-WINDOW wanted " + x + "," + y + " moved=" + moved + " now " + nx.get(0) + "," + ny.get(0));
		return moved;
	}
}
