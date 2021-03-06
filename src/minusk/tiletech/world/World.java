package minusk.tiletech.world;

import minusk.tiletech.render.GLHandler;
import minusk.tiletech.utils.Direction;
import minusk.tiletech.utils.OpenSimplexNoise;
import minusk.tiletech.utils.UniqueQueue;
import minusk.tiletech.world.entities.Player;
import minusk.tiletech.world.structures.Cave;
import minusk.tiletech.world.tiles.Tile;
import org.joml.*;

import java.lang.Math;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static minusk.tiletech.utils.Direction.*;
import static minusk.tiletech.utils.Util.*;
import static minusk.tiletech.world.LightChannel.*;
import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glUniform4f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.system.jemalloc.JEmalloc.je_malloc;

/**
 * +Y = Up
 * -Y = Down
 * +X = West
 * -X = East
 * +Z = North
 * -Z = South
 * 
 * Created by MinusKelvin on 1/25/16.
 */
public final class World {
	private static World currentWorld;
	
	public final Player player = new Player();
	public final long seed = 534634958076L;
	public final OpenSimplexNoise noise1 = new OpenSimplexNoise();
	public final OpenSimplexNoise noise2 = new OpenSimplexNoise(seed*31);
	public final OpenSimplexNoise noise3 = new OpenSimplexNoise(seed*31*31);
	
	private final ConcurrentHashMap<Vector2i, VerticalChunk> world = new ConcurrentHashMap<>(4096);
	private final HashMap<Vector2i, Cave> caves = new HashMap<>(4096);
	private final Matrix4f lookaround = new Matrix4f(), shadowCam = new Matrix4f();
	private final ByteBuffer matrixUpload = je_malloc(64);
	private final FrustumIntersection culler = new FrustumIntersection();
	private final Vector2i index2a = new Vector2i();
	private final Vector2i index2b = new Vector2i();
	private final Vector3i index3 = new Vector3i();
	private final Vector<Vector3i> updateList = new Vector<>();
	private final Queue<Vector3i> lightUpdatePoints = new UniqueQueue<>(512);
	private final Vector<Vector3i> generatePoints = new Vector<>();
	private final Vector3f sundir = new Vector3f(0.6f, 0.4f, 0.3f).normalize(), last = new Vector3f(sundir);
	
	public World() {
		currentWorld = this;
		for (int i = -4; i <= 4; i++)
			for (int j = -4; j <= 4; j++)
				rawGenerateChunk(i,j,0);
		player.spawn();
		Thread worldGenThread = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				if (!generatePoints.isEmpty()) {
					Vector3i v = generatePoints.remove(0);
					rawGenerateChunk(v.x, v.y, v.z);
				}
			}
		});
		worldGenThread.setDaemon(true);
		worldGenThread.setName("World Generation Thread");
		worldGenThread.setPriority(Thread.MIN_PRIORITY);
		for (int i = -12; i <= 12; i++)
			for (int j = -12; j <= 12; j++)
				if (i < -4 || i > 4 || j < -4 || j > 4)
					generatePoints.add(new Vector3i(i,j,0));
		generatePoints.sort(Comparator.comparingLong(Vector3i::lengthSquared));
		worldGenThread.start();
	}
	
	public Tile getTile(int x, int y, int z, int dim) {
		if (y < 0 || y >= 256)
			return Tile.getTile(0);
		else if (!world.containsKey(index2a.set(getCnk(x), getCnk(z))))
			return Tile.getTile((short) 5);
		else
			return Tile.getTile(world.get(index2a).chunks[getCnk(y)].getTile(cnkIdx(x),cnkIdx(y),cnkIdx(z)));
	}
	
	public int getLight(int x, int y, int z, int dim, LightChannel channel) {
		Chunk chunk = getChunk(getCnk(x), getCnk(y), getCnk(z), dim);
		if (chunk == null)
			return 0;
		return chunk.getLight(cnkIdx(x),cnkIdx(y),cnkIdx(z), channel);
	}
	
	public Object getMeta(int x, int y, int z, int dim) {
		if (y < 0 || y >= 256)
			return null;
		return world.get(index2a.set(getCnk(x),getCnk(z))).chunks[getCnk(y)].blockMeta.get(index3.set(cnkIdx(x),cnkIdx(y),cnkIdx(z)));
	}
	
	public Chunk getChunk(int x, int y, int z, int dim) {
		if (y < 0 || y >= 8)
			return null;
		if (!world.containsKey(index2a.set(x, z)))
			return null;
		return world.get(index2a).chunks[y];
	}
	
	public void setTile(int x, int y, int z, int dim, short id) {
		Chunk chunk = getChunk(getCnk(x), getCnk(y), getCnk(z), dim);
		if (chunk != null)
			chunk.setTile(cnkIdx(x),cnkIdx(y),cnkIdx(z),id);
	}
	
	public void setLight(int x, int y, int z, int dim, LightChannel channel, int amount) {
		Chunk chunk = getChunk(getCnk(x), getCnk(y), getCnk(z), dim);
		if (chunk != null)
			chunk.setLight(cnkIdx(x),cnkIdx(y),cnkIdx(z), channel, amount);
	}
	
	public Tile genGetTile(int x, int y, int z, int dim) {
		if (y < 0)
			return Tile.getTile((short) 5);
		else if (y >= 256)
			return Tile.getTile((short) 0);
		else if (!world.containsKey(index2b.set(getCnk(x), getCnk(z))))
			return Tile.getTile((short) 5);
		else
			return Tile.getTile(world.get(index2b).chunks[getCnk(y)].getTile(cnkIdx(x),cnkIdx(y),cnkIdx(z)));
	}
	
	public boolean genLimitedReplace(int x, int y, int z, int dim, short[] canReplace, short id) {
		if (!world.containsKey(index2b.set(getCnk(x),getCnk(z))))
			rawGenerateChunk(getCnk(x),getCnk(z),dim);
		Chunk chunk = world.get(index2b.set(getCnk(x),getCnk(z))).chunks[getCnk(y)];
		if (contains(chunk.getTile(cnkIdx(x),cnkIdx(y),cnkIdx(z)), canReplace)) {
			chunk.rawSetTile(cnkIdx(x),cnkIdx(y),cnkIdx(z),id);
			if (!chunk.needsUpdate) {
				chunk.needsUpdate = true;
				updateList.add(new Vector3i(getCnk(x),getCnk(y),getCnk(z)));
			}
			return true;
		}
		return false;
	}
	
	public void requestChunkRender(int x, int y, int z, int dim) {
		Chunk chunk = getChunk(x,y,z,dim);
		if (chunk == null)
			return;
		chunk.needsUpdate = true;
		updateList.add(new Vector3i(x,y,z));
	}
	
	public void requestLightUpdate(int x, int y, int z, int dim) {
		synchronized (lightUpdatePoints) {
			lightUpdatePoints.add(new Vector3i(x, y, z));
		}
	}
	
	public int random(int min, int max) {
		return (int) (Math.random()*(max-min)+min);
	}
	
	public void tick() {
		player.update();
		last.set(sundir);
		sundir.rotate(new Quaternionf(new AxisAngle4f(0.000087266f, 0, 0, 1)));
		
		double t = glfwGetTime();
		for (int i = 0; i < 2048; i++) {
			if (lightUpdatePoints.isEmpty())
				break;
			Vector3i tilepos;
			synchronized (lightUpdatePoints) {
				tilepos = lightUpdatePoints.poll();
			}
			lightTile(tilepos.x, tilepos.y, tilepos.z, 0);
		}
		t = (glfwGetTime() - t) * 1000;
		if (t > 1)
			System.out.println("Lighting: " + t + " ms");
		if (lightUpdatePoints.size() > 0)
			System.out.println(lightUpdatePoints.size());
	}
	
	private void lightTile(int x, int y, int z, int dim) {
		Tile thisTile = getTile(x,y,z,dim);
		
		boolean lit = y == 255 && thisTile.isTransparentTop(x,y,z,dim);
		int neighbourMaxSun = 0;
		int neighbourMaxRed = 0;
		int neighbourMaxGreen = 0;
		int neighbourMaxBlue = 0;
		
		if (thisTile.isTransparentNorth(x,y,z,dim)) {
			int olight = getLight(x,y,z-1,dim, null);
			int osun = olight >> SUN.getShiftAmmount() & 0xF;
			int ored = olight >> RED.getShiftAmmount() & 0xF;
			int ogreen = olight >> GREEN.getShiftAmmount() & 0xF;
			int oblue = olight >> BLUE.getShiftAmmount() & 0xF;
			if (osun > neighbourMaxSun)
				neighbourMaxSun = osun;
			if (ored > neighbourMaxRed)
				neighbourMaxRed = ored;
			if (ogreen > neighbourMaxGreen)
				neighbourMaxGreen = ogreen;
			if (oblue > neighbourMaxBlue)
				neighbourMaxBlue = oblue;
		}
		if (thisTile.isTransparentSouth(x,y,z,dim)) {
			int olight = getLight(x,y,z+1,dim, null);
			int osun = olight >> SUN.getShiftAmmount() & 0xF;
			int ored = olight >> RED.getShiftAmmount() & 0xF;
			int ogreen = olight >> GREEN.getShiftAmmount() & 0xF;
			int oblue = olight >> BLUE.getShiftAmmount() & 0xF;
			if (osun > neighbourMaxSun)
				neighbourMaxSun = osun;
			if (ored > neighbourMaxRed)
				neighbourMaxRed = ored;
			if (ogreen > neighbourMaxGreen)
				neighbourMaxGreen = ogreen;
			if (oblue > neighbourMaxBlue)
				neighbourMaxBlue = oblue;
		}
		if (thisTile.isTransparentWest(x,y,z,dim)) {
			int olight = getLight(x-1,y,z,dim, null);
			int osun = olight >> SUN.getShiftAmmount() & 0xF;
			int ored = olight >> RED.getShiftAmmount() & 0xF;
			int ogreen = olight >> GREEN.getShiftAmmount() & 0xF;
			int oblue = olight >> BLUE.getShiftAmmount() & 0xF;
			if (osun > neighbourMaxSun)
				neighbourMaxSun = osun;
			if (ored > neighbourMaxRed)
				neighbourMaxRed = ored;
			if (ogreen > neighbourMaxGreen)
				neighbourMaxGreen = ogreen;
			if (oblue > neighbourMaxBlue)
				neighbourMaxBlue = oblue;
		}
		if (thisTile.isTransparentEast(x,y,z,dim)) {
			int olight = getLight(x+1,y,z,dim, null);
			int osun = olight >> SUN.getShiftAmmount() & 0xF;
			int ored = olight >> RED.getShiftAmmount() & 0xF;
			int ogreen = olight >> GREEN.getShiftAmmount() & 0xF;
			int oblue = olight >> BLUE.getShiftAmmount() & 0xF;
			if (osun > neighbourMaxSun)
				neighbourMaxSun = osun;
			if (ored > neighbourMaxRed)
				neighbourMaxRed = ored;
			if (ogreen > neighbourMaxGreen)
				neighbourMaxGreen = ogreen;
			if (oblue > neighbourMaxBlue)
				neighbourMaxBlue = oblue;
		}
		if (thisTile.isTransparentBottom(x,y,z,dim)) {
			int olight = getLight(x,y-1,z,dim, null);
			int osun = olight >> SUN.getShiftAmmount() & 0xF;
			int ored = olight >> RED.getShiftAmmount() & 0xF;
			int ogreen = olight >> GREEN.getShiftAmmount() & 0xF;
			int oblue = olight >> BLUE.getShiftAmmount() & 0xF;
			if (osun > neighbourMaxSun)
				neighbourMaxSun = osun;
			if (ored > neighbourMaxRed)
				neighbourMaxRed = ored;
			if (ogreen > neighbourMaxGreen)
				neighbourMaxGreen = ogreen;
			if (oblue > neighbourMaxBlue)
				neighbourMaxBlue = oblue;
		}
		if (thisTile.isTransparentTop(x,y,z,dim)) {
			int olight = getLight(x,y+1,z,dim, null);
			int osun = olight >> SUN.getShiftAmmount() & 0xF;
			int ored = olight >> RED.getShiftAmmount() & 0xF;
			int ogreen = olight >> GREEN.getShiftAmmount() & 0xF;
			int oblue = olight >> BLUE.getShiftAmmount() & 0xF;
			if (osun > neighbourMaxSun)
				neighbourMaxSun = osun;
			if (osun == 15)
				lit = true;
			if (ored > neighbourMaxRed)
				neighbourMaxRed = ored;
			if (ogreen > neighbourMaxGreen)
				neighbourMaxGreen = ogreen;
			if (oblue > neighbourMaxBlue)
				neighbourMaxBlue = oblue;
		}
		
		int sun = lit ? 15 : thisTile.getLuminosity(x,y,z,dim, SUN);
		int red = thisTile.getLuminosity(x,y,z,dim, RED);
		int green = thisTile.getLuminosity(x,y,z,dim, GREEN);
		int blue = thisTile.getLuminosity(x,y,z,dim, BLUE);
		
		if (sun < neighbourMaxSun) sun = neighbourMaxSun - 1;
		if (red < neighbourMaxRed) red = neighbourMaxRed - 1;
		if (green < neighbourMaxGreen) green = neighbourMaxGreen - 1;
		if (blue < neighbourMaxBlue) blue = neighbourMaxBlue - 1;
		
		int light = 0;
		light |= sun << SUN.getShiftAmmount();
		light |= red << RED.getShiftAmmount();
		light |= green << GREEN.getShiftAmmount();
		light |= blue << BLUE.getShiftAmmount();
		setLight(x,y,z,dim, null, light);
	}
	
	public void renderWorld(float alpha) {
		Vector3f effectiveSunDir = new Vector3f(last).lerp(sundir, alpha).normalize();
		
		lookaround.identity();
		lookaround.lookAlong(player.look.x, player.look.y, player.look.z, 0, 1, 0);
		Vector3f eye = player.getEye(alpha);
		lookaround.translate(-eye.x, -eye.y, -eye.z);
		GLHandler.projection.mul(lookaround, lookaround);
		
		for (int i = Math.round(eye.x / 32) - 1; i < Math.round(eye.x / 32) + 1; i++) {
			for (int j = Math.round(eye.y / 32) - 1; j < Math.round(eye.y / 32) + 1; j++) {
				for (int k = Math.round(eye.z / 32) - 1; k < Math.round(eye.z / 32) + 1; k++) {
					Chunk chunk = getChunk(i, j, k, 0);
					if (chunk != null && chunk.needsUpdate) {
						chunk.needsUpdate = false;
						chunk.updateVBO();
						updateList.remove(new Vector3i(i,j,k));
					}
				}
			}
		}
		
		int c = 0;
		Vector3i[] closest = new Vector3i[8];
		int[] distances = new int[8];
		outer:
		for (int i = 0; i < updateList.size(); i++) {
			int far = 0;
			Vector3i current = updateList.get(i);
			for (int j = 0; j < 8; j++) {
				if (closest[j] == null) {
					closest[j] = current;
					distances[j] = (int) current.distanceSquared(getCnk((int) Math.floor(player.center.x)),
							getCnk((int) Math.floor(player.center.y)), getCnk((int) Math.floor(player.center.z)));
					continue outer;
				} else if (distances[far] > distances[j])
					far = j;
			}
			int dist = (int) current.distanceSquared(getCnk((int) Math.floor(player.center.x)),
					getCnk((int) Math.floor(player.center.y)), getCnk((int) Math.floor(player.center.z)));
			if (dist < distances[far] || (culler.testAab(current.x*32, current.y*32, current.z*32, current.x*32+32, current.y*32+32, current.z*32+32) &&
					!culler.testAab(closest[far].x*32, closest[far].y*32, closest[far].z*32, closest[far].x*32+32, closest[far].y*32+32, closest[far].z*32+32))) {
				closest[far] = current;
				distances[far] = dist;
			}
		}
		updateList.removeAll(Arrays.asList(closest));
		for (int i = 0; i < 8; i++) {
			if (closest[i] != null) {
				getChunk(closest[i].x, closest[i].y, closest[i].z, 0).needsUpdate = false;
				getChunk(closest[i].x, closest[i].y, closest[i].z, 0).updateVBO();
			}
		}
		
		glCullFace(GL_FRONT);
		glDepthFunc(GL_LEQUAL);
		
		for (int i = 0; i < 4; i++) {
			GLHandler.prepareShadow(i);
			shadowCam.setOrtho(-4*intpow(4,i), 4*intpow(4,i), -4*intpow(4,i), 4*intpow(4,i), 256, -256);
			shadowCam.lookAlong(effectiveSunDir.x, effectiveSunDir.y, effectiveSunDir.z, 0, 1, 0);
			shadowCam.translate(-Math.round(eye.x), -Math.round(eye.y), -Math.round(eye.z));
			
			shadowCam.get(matrixUpload);
			glUniformMatrix4fv(GLHandler.getShadowProjLoc(), false, matrixUpload.asFloatBuffer());
			culler.set(shadowCam);
			
			world.values().forEach(cp -> {
				if (cp.isGened())
					cp.render(culler, true);
			});
		}
		shadowCam.setOrtho(-4, 4, -4, 4, 256, -256);
		shadowCam.lookAlong(effectiveSunDir.x, effectiveSunDir.y, effectiveSunDir.z, 0, 1, 0);
		shadowCam.translate(-Math.round(eye.x), -Math.round(eye.y), -Math.round(eye.z));
		shadowCam.get(matrixUpload);
		
		glCullFace(GL_BACK);
		
		float sunpower = effectiveSunDir.dot(0, 1, 0) * 2;
		sunpower = sunpower > 1 ? 1 : sunpower < 0 ? 0 : sunpower;
		GLHandler.prepareScene(sunpower);
		glUniformMatrix4fv(GLHandler.getSprojLoc(), false, matrixUpload.asFloatBuffer());
		lookaround.get(matrixUpload);
		glUniformMatrix4fv(GLHandler.getProjLoc(), false, matrixUpload.asFloatBuffer());
		glUniform4f(GLHandler.getSundirLoc(), -effectiveSunDir.x, effectiveSunDir.y, -effectiveSunDir.z, sunpower);
		culler.set(lookaround);
		
		world.values().forEach(cp -> {
			if (cp.isGened())
				cp.render(culler, false);
		});
		
		player.render(false);
	}
	
	private int intpow(int b, int e) {
		int total = 1;
		for (int i = 0; i < e; i++)
			total *= b;
		return total;
	}
	
	private VerticalChunk getChunk(int x, int z, int dim) {
		return world.get(index2b.set(x,z));
	}
	
	private void checkChunk(int x, int z, int dim) {
		VerticalChunk vc = getChunk(x,z,dim);
		if (vc != null && getChunk(x-1,z,dim) != null && getChunk(x+1,z,dim) != null && getChunk(x,z-1,dim) != null && getChunk(x,z+1,dim) != null &&
				getChunk(x-1,z-1,dim) != null && getChunk(x+1,z+1,dim) != null && getChunk(x+1,z-1,dim) != null && getChunk(x-1,z+1,dim) != null) {
			vc.generate();
			for (int i = 0; i < 8; i++) {
				updateList.add(new Vector3i(x, i, z));
				vc.chunks[i].needsUpdate = true;
			}
		}
	}
	
	private void rawGenerateChunk(int x, int z, int dim) {
		ArrayList<Cave.Segment> nearbySegments = new ArrayList<>();
		for (int i = x-6; i <= x+6; i++) {
			for (int j = z-6; j <= z+6; j++) {
				if (!caves.containsKey(new Vector2i(i,j)))
					genCave(i,j);
				assert caves.containsKey(new Vector2i(i,j));
				Cave cave = caves.get(new Vector2i(i,j));
				for (Cave.Segment seg : cave.segments) {
					if (new Vector2f(seg.p1.x,seg.p1.z).sub(x*32+16,z*32+16).lengthSquared() < 1024 ||
							new Vector2f(seg.p2.x,seg.p2.z).sub(x*32+16,z*32+16).lengthSquared() < 1024) {
						nearbySegments.add(seg);
					}
				}
			}
		}
		
		world.put(new Vector2i(x,z), new VerticalChunk(x*32,z*32,dim,nearbySegments));
		checkChunk(x-1,z,dim);
		checkChunk(x+1,z,dim);
		checkChunk(x,z-1,dim);
		checkChunk(x,z+1,dim);
		checkChunk(x-1,z-1,dim);
		checkChunk(x+1,z+1,dim);
		checkChunk(x+1,z-1,dim);
		checkChunk(x-1,z+1,dim);
	}
	
	private void genCave(int x, int z) {
		Cave cave = new Cave();
		Random rng = new Random(seed + x*17317L + z*5557L);
		int segcount = (int) ((rng.nextDouble()+0.25)*48);
		float h = rng.nextInt(256);
		Vector3f pos = new Vector3f(x*32+rng.nextInt(32), (h*h)/256, z*32+rng.nextInt(32));
		for (int i = 0; i < segcount; i++) {
			Vector3f p = new Vector3f(pos);
			pos.x += noise3.eval(x*4, z-26, i/8.0) * 10;
			pos.y += noise3.eval(x-26, z*4, i/8.0) * 5;
			pos.z += noise3.eval(x*4, z*4, i/8.0) * 10;
			if (getCnk((int) pos.x) >= x+6 || getCnk((int) pos.x) <= x-6 || getCnk((int) pos.z) >= z+6 || getCnk((int) pos.z) <= z-6) 
				break;
			cave.segments.add(new Cave.Segment(p, new Vector3f(pos),
					(float) (noise3.eval(x*4, z*4, -i/5.0)+1.25) * 2,
					(float) (noise3.eval(x*4, z*4, -0.2-i/5.0)+1.25) * 2));
		}
		caves.put(new Vector2i(x,z), cave);
	}
	
	public RaytraceResult raytrace(float posx, float posy, float posz, int dimension, float dirx, float diry, float dirz, float maxBlocks) {
		RayAabIntersection intersect = new RayAabIntersection(posx, posy, posz, dirx, diry, dirz);
		
		int cx = (int) Math.floor(posx), cy = (int) Math.floor(posy), cz = (int) Math.floor(posz);
		int dx = (int) Math.signum(dirx), dy = (int) Math.signum(diry), dz = (int) Math.signum(dirz);
		
		if (getTile(cx,cy,cz,dimension).raytrace(cx,cy,cz,dimension,intersect)) {
			Direction side;
			if (Math.abs(dirx) > Math.abs(diry)) {
				if (Math.abs(dirx) > Math.abs(dirz)) {
					if (dx == 1)
						side = WEST; // if looking +X you see the -X face
					else
						side = EAST; // if looking -X you see the +X face
				} else {
					if (dz == 1)
						side = NORTH; // if looking +Z you see the -Z face
					else
						side = SOUTH; // if looking -Z you see the +Z face
				}
			} else {
				if (Math.abs(diry) > Math.abs(dirz)) {
					if (dy == 1)
						side = DOWN; // if looking +Y you see the -Y face
					else
						side = UP; // if looking -Y you see the +Y face
				} else {
					if (dz == 1)
						side = NORTH; // if looking +Z you see the -Z face
					else
						side = SOUTH; // if looking -Z you see the +Z face
				}
			}
			return new RaytraceResult(new Vector3i(cx, cy, cz), side);
		}
		
		for (int i = 0; i < maxBlocks; i++) {
			if (maxBlocks-i < 1) {
				dirx *= maxBlocks-i;
				diry *= maxBlocks-i;
				dirz *= maxBlocks-i;
			}
			float oldx = posx;
			float oldy = posy;
			float oldz = posz;
			
			posx += dirx;
			posy += diry;
			posz += dirz;
			
			boolean xchange = Math.floor(oldx) != Math.floor(posx);
			boolean ychange = Math.floor(oldy) != Math.floor(posy);
			boolean zchange = Math.floor(oldz) != Math.floor(posz);
			int changes = (xchange?1:0) + (ychange?1:0) + (zchange?1:0);
			
			for (int j = 0; j < changes; j++) {
				if (xchange && intersect.test(cx+dx,cy,cz,cx+dx+1,cy+1,cz+1)) {
					cx += dx;
					if (getTile(cx,cy,cz,dimension).raytrace(cx,cy,cz,dimension,intersect)) {
						Direction side;
						if (dx == 1)
							side = WEST; // if looking +X you see the -X face
						else
							side = EAST; // if looking -X you see the +X face
						return new RaytraceResult(new Vector3i(cx, cy, cz), side);
					}
					xchange = false;
				} else if (ychange && intersect.test(cx,cy+dy,cz,cx+1,cy+dy+1,cz+1)) {
					cy += dy;
					if (getTile(cx,cy,cz,dimension).raytrace(cx,cy,cz,dimension,intersect)) {
						Direction side;
						if (dy == 1)
							side = DOWN; // if looking +Y you see the -Y face
						else
							side = UP; // if looking -Y you see the +Y face
						return new RaytraceResult(new Vector3i(cx, cy, cz), side);
					}
					ychange = false;
				} else if (zchange && intersect.test(cx,cy,cz+dz,cx+1,cy+1,cz+dz+1)) {
					cz += dz;
					if (getTile(cx,cy,cz,dimension).raytrace(cx,cy,cz,dimension,intersect)) {
						Direction side;
						if (dz == 1)
							side = NORTH; // if looking +Z you see the -Z face
						else
							side = SOUTH; // if looking -Z you see the +Z face
						return new RaytraceResult(new Vector3i(cx, cy, cz), side);
					}
					zchange = false;
				}
			}
		}
		return null;
	}
	
	public static class RaytraceResult {
		public final Vector3ic pos;
		public final Direction side;
		
		private RaytraceResult(Vector3ic pos, Direction side) {
			this.pos = pos;
			this.side = side;
		}
	}
	
	public static World getWorld() {
		return currentWorld;
	}
}
