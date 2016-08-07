package minusk.tiletech.world;

import org.joml.Vector3i;

import java.nio.ByteBuffer;
import java.util.HashMap;

import static minusk.tiletech.utils.Util.getCnk;
import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.system.jemalloc.JEmalloc.je_malloc;
import static org.lwjgl.system.jemalloc.JEmalloc.je_realloc;

/**
 * Created by MinusKelvin on 1/25/16.
 */
public class Chunk {
	// Note: All of these block arrays are [x][z][y]
	int[][][] blockIDs = new int[32][32][32];
	HashMap<Vector3i, Object> blockMeta = new HashMap<>(32);
	private int vbo = -1, verts, x,y,z,dim;
	boolean needsUpdate = false;
	
	public Chunk(int x, int y, int z, int dim, int[][] hs) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.dim = dim;
		
		for (int i = 0; i < 32; i++) {
			for (int j = 0; j < 32; j++) {
				for (int k = 0; k < 32; k++) {
					if (k+y == 0)
						blockIDs[i][j][k] = Tile.Bedrock.id;
					else if (k + y > hs[i][j])
						blockIDs[i][j][k] = Tile.Air.id;
					else if (k + y == hs[i][j])
						blockIDs[i][j][k] = Tile.Grass.id;
					else if (k+y >= hs[i][j]-3)
						blockIDs[i][j][k] = Tile.Dirt.id;
					else
						blockIDs[i][j][k] = Tile.Stone.id;
				}
			}
		}
	}
	
	private static int maxVerts = 1024;
	private static ByteBuffer data = je_malloc(maxVerts*44);
	private static Tile[][][] chunkEdges = new Tile[34][34][34];
	
	void updateVBO() {
		double t = glfwGetTime();
		
		verts = 0;
		data.position(0);
		
		for (int i = 0; i < 32; i++) {
			for (int j = 0; j < 32; j++) {
				for (int k = 0; k < 32; k++)
					chunkEdges[i + 1][j + 1][k + 1] = Tile.TILES[blockIDs[i][j][k]];
				if (y != 0) chunkEdges[i+1][j+1][0] = World.getWorld().getTile(x+i,y-1,z+j,dim);
				else chunkEdges[i+1][j+1][0] = Tile.Bedrock;
				chunkEdges[i+1][j+1][33] = World.getWorld().getTile(x+i,y+32,z+j,dim);
				chunkEdges[0][j+1][i+1] = World.getWorld().getTile(x-1,y+i,z+j,dim);
				chunkEdges[33][j+1][i+1] = World.getWorld().getTile(x+32,y+i,z+j,dim);
				chunkEdges[i+1][0][j+1] = World.getWorld().getTile(x+i,y+j,z-1,dim);
				chunkEdges[i+1][33][j+1] = World.getWorld().getTile(x+i,y+j,z+32,dim);
			}
			chunkEdges[i+1][0][33] = World.getWorld().getTile(x+i,y+32,z-1,dim);
			chunkEdges[i+1][33][33] = World.getWorld().getTile(x+i,y+32,z+32,dim);
			chunkEdges[0][0][i+1] = World.getWorld().getTile(x-1,y+i,z-1,dim);
			chunkEdges[33][0][i+1] = World.getWorld().getTile(x+32,y+i,z-1,dim);
			chunkEdges[0][33][i+1] = World.getWorld().getTile(x-1,y+i,z+32,dim);
			chunkEdges[33][33][i+1] = World.getWorld().getTile(x+32,y+i,z+32,dim);
			chunkEdges[0][i+1][33] = World.getWorld().getTile(x-1,y+32,z+i,dim);
			chunkEdges[33][i+1][33] = World.getWorld().getTile(x+32,y+32,z+i,dim);
			if (y != 0) {
				chunkEdges[0][i + 1][0] = World.getWorld().getTile(x - 1, y - 1, z + i, dim);
				chunkEdges[33][i + 1][0] = World.getWorld().getTile(x + 32, y - 1, z + i, dim);
				chunkEdges[i + 1][0][0] = World.getWorld().getTile(x + i, y - 1, z - 1, dim);
				chunkEdges[i + 1][33][0] = World.getWorld().getTile(x + i, y - 1, z + 32, dim);
			} else {
				chunkEdges[0][i + 1][0] = Tile.Bedrock;
				chunkEdges[33][i + 1][0] = Tile.Bedrock;
				chunkEdges[i + 1][0][0] = Tile.Bedrock;
				chunkEdges[i + 1][33][0] = Tile.Bedrock;
			}
		}
		chunkEdges[0][0][33] = World.getWorld().getTile(x-1,y+32,z-1,dim);
		chunkEdges[0][33][33] = World.getWorld().getTile(x-1,y+32,z+32,dim);
		chunkEdges[33][0][33] = World.getWorld().getTile(x+32,y+32,z-1,dim);
		chunkEdges[33][33][33] = World.getWorld().getTile(x+32,y+32,z+32,dim);
		if (y != 0) {
			chunkEdges[0][0][0] = World.getWorld().getTile(x - 1, y - 1, z - 1, dim);
			chunkEdges[0][33][0] = World.getWorld().getTile(x - 1, y - 1, z + 32, dim);
			chunkEdges[33][0][0] = World.getWorld().getTile(x + 32, y - 1, z - 1, dim);
			chunkEdges[33][33][0] = World.getWorld().getTile(x + 32, y - 1, z + 32, dim);
		} else {
			chunkEdges[0][0][0] = Tile.Bedrock;
			chunkEdges[0][33][0] = Tile.Bedrock;
			chunkEdges[33][0][0] = Tile.Bedrock;
			chunkEdges[33][33][0] = Tile.Bedrock;
		}
		
		for (int i = 0; i < 32; i++) {
			for (int j = 0; j < 32; j++) {
				for (int k = 0; k < 32; k++) {
					int returnedVerts = chunkEdges[i+1][j+1][k+1].render(chunkEdges, data, maxVerts - verts, x+i, y+k, z+j, dim);
					while (returnedVerts == -1) {
						maxVerts += 1024;
						int pos = data.position();
						data.position(0);
						data = je_realloc(data, maxVerts*44);
						data.position(pos);
						returnedVerts = chunkEdges[i+1][j+1][k+1].render(chunkEdges, data, maxVerts- verts, x+i, y+k, z+j, dim);
					}
					verts += returnedVerts;
				}
			}
		}
		
		data.position(0);
		
		if (verts != 0) {
			if (vbo == -1)
				vbo = glGenBuffers();
			glBindBuffer(GL_ARRAY_BUFFER, vbo);
			glBufferData(GL_ARRAY_BUFFER, verts * 44, data, GL_STATIC_DRAW);
		}
		
		double t2 = glfwGetTime() - t;
		if (t2 > 0.01)
			System.out.println(verts + " verts: " + Math.round(t2*1000) + " ms "+x+","+y+","+z);
	}
	
	void render(boolean shadowPass) {
		if (verts == 0)
			return;
		
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 44, 0);
		glVertexAttribPointer(1, 3, GL_FLOAT, false, 44, 12);
		if (!shadowPass) {
			glVertexAttribPointer(2, 3, GL_FLOAT, false, 44, 24);
			glVertexAttribPointer(3, 2, GL_UNSIGNED_SHORT, true, 44, 36);
			glVertexAttribPointer(4, 4, GL_UNSIGNED_BYTE, true, 44, 40);
		}
		glEnableVertexAttribArray(0);
		glEnableVertexAttribArray(1);
		if (shadowPass) {
			glDisableVertexAttribArray(2);
			glDisableVertexAttribArray(3);
			glDisableVertexAttribArray(4);
		} else {
			glEnableVertexAttribArray(2);
			glEnableVertexAttribArray(3);
			glEnableVertexAttribArray(4);
		}
		
		glDrawArrays(GL_TRIANGLES, 0, verts);
	}
	
	public void setTile(int x, int y, int z, int id) {
		System.out.println("Setblock: " + (this.x+x) + ", " + (this.y+y) + ", " + (this.z+z) + " old: " + blockIDs[x][z][y]);
		Tile.TILES[blockIDs[x][z][y]].onDelete(x+this.x, y+this.y, z+this.z, dim);
		blockIDs[x][z][y] = id;
		blockMeta.remove(new Vector3i(x,y,z));
		Tile.TILES[id].onCreate(x+this.x, y+this.y, z+this.z, dim);
		for (int i = -1; i < 2; i++)
			for (int j = -1; j < 2; j++)
				for (int k = -1; k < 2; k++)
					World.getWorld().requestChunkRender(i+getCnk(x+this.x), k+getCnk(y+this.y), j+getCnk(z+this.z), dim);
	}
}
