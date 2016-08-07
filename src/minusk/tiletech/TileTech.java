package minusk.tiletech;

import minusk.tiletech.gui.Gui;
import minusk.tiletech.render.GLHandler;
import minusk.tiletech.world.World;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_COLOR;
import static org.lwjgl.opengl.GL11.GL_DEPTH;
import static org.lwjgl.opengl.GL30.glClearBufferfv;
import static org.lwjgl.system.jemalloc.JEmalloc.je_malloc;

/**
 * Created by MinusKelvin on 1/22/16.
 */
public class TileTech {
	public static final TileTech game = new TileTech();
	
	private long window;
	private boolean paused = false;
	private double now;
	
	private void run() {
		glfwInit();
		
		glfwDefaultWindowHints();
		glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
		glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
		glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
		
		window = glfwCreateWindow(1024, 576, "Tile Tech", 0, 0);
		glfwMakeContextCurrent(window);
		GL.createCapabilities();
		glfwSwapInterval(0);
		
		GLHandler.init(window);
		Gui.init();
		new World();
		
		ByteBuffer clearColor = je_malloc(20);
		clearColor.putFloat(0, 0.25f);
		clearColor.putFloat(4, 0.5f);
		clearColor.putFloat(8, 1);
		clearColor.putFloat(12,1);
		clearColor.putFloat(16,1);
		ByteBuffer clearDepth = ((ByteBuffer) clearColor.position(16)).slice().order(ByteOrder.nativeOrder());
		clearDepth.putFloat(0, 1);
		clearColor.position(0);
		
		double time = glfwGetTime();
		float between = 0;
		while (true) {
			double t = glfwGetTime();
			if (glfwWindowShouldClose(window) == 1)
				break;
			
			int c = 0;
			while (glfwGetTime() - time >= 0.05) {
				if (!paused) {
					time += 0.05;
					World.getWorld().tick();
				}
				Gui.tock();
				GLHandler.clearTaps();
				if (c++ == 5 && !paused) {
					time = glfwGetTime();
					break;
				}
				if (paused)
					break;
			}
			
			glClearBufferfv(GL_COLOR, 0, clearColor);
			glClearBufferfv(GL_DEPTH, 0, clearDepth);
			
			if (!paused)
				between = (float) ((glfwGetTime()-time) / 0.05);
			else
				between = (float) ((now - time) / 0.05);
			World.getWorld().renderWorld(between);
			Gui.render();
			
			glfwSwapBuffers(window);
			glfwPollEvents();
//			System.out.println((glfwGetTime() - t) * 1000);
		}
	}
	
	public void pause() {
		paused = true;
		now = glfwGetTime();
	}
	
	public void unpause() {
		paused = false;
		glfwSetTime(now);
	}
	
	public static void main(String[] args) {
		Thread.currentThread().setPriority(Thread.MAX_PRIORITY-1);
		game.run();
	}
}
