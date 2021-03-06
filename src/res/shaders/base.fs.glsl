#version 330 core

in vec3 texcoord;
in vec3 normal;
in vec2 quad;
flat in vec4 ao;
in vec4 c;
in vec3 shadowpos;

uniform sampler2DArray tex;
uniform sampler2D shadow1;
uniform sampler2D shadow2;
uniform sampler2D shadow3;
uniform sampler2D shadow4;
uniform vec4 sundir;

out vec4 color;

void main() {
	color = texture(tex, texcoord);
	if (color.a < 0.5)
		discard;
	color.a = 1;
	float cosTheta = dot(sundir.xyz, normalize(normal.xyz));
	float shadowValue = 1.0 - float(shadowpos.x > -1.0 && shadowpos.x < 1.0 && shadowpos.y > -1.0 && shadowpos.y < 1.0) *
			step(texture(shadow1, shadowpos.xy * 0.5 + 0.5).r, shadowpos.z * 0.5 + 0.5 - 0.0001) * float(shadowpos.z <= 1);
	shadowValue *= 1.0 - float(shadowpos.x > -4.0 && shadowpos.x < 4.0 && shadowpos.y > -4.0 && shadowpos.y < 4.0) *
			float(shadowpos.x <= -1.0 || shadowpos.x >= 1.0 || shadowpos.y <= -1.0 || shadowpos.y >= 1.0) *
			step(texture(shadow2, shadowpos.xy * 0.5/4.0 + 0.5).r, shadowpos.z * 0.5 + 0.5 - 0.0002) * float(shadowpos.z <= 1);
	shadowValue *= 1.0 - float(shadowpos.x > -16.0 && shadowpos.x < 16.0 && shadowpos.y > -16.0 && shadowpos.y < 16.0) *
			float(shadowpos.x <= -4.0 || shadowpos.x >= 4.0 || shadowpos.y <= -4.0 || shadowpos.y >= 4.0) *
			step(texture(shadow3, shadowpos.xy * 0.5/16.0 + 0.5).r, shadowpos.z * 0.5 + 0.5 - 0.0005) * float(shadowpos.z <= 1);
	shadowValue *= 1.0 - float(shadowpos.x > -64.0 && shadowpos.x < 64.0 && shadowpos.y > -64.0 && shadowpos.y < 64.0) *
			float(shadowpos.x <= -16.0 || shadowpos.x >= 16.0 || shadowpos.y <= -16.0 || shadowpos.y >= 16.0) *
			step(texture(shadow4, shadowpos.xy * 0.5/64.0 + 0.5).r, shadowpos.z * 0.5 + 0.5 - 0.001) * float(shadowpos.z <= 1);
	vec3 factor = vec3((min(1.0, 0.5 + min(max(0.0, cosTheta), shadowValue)) * max(0.0, sundir.w-0.1) / 0.9 + 0.08) / 1.08);
	factor *= c.a /*mix(mix(c0.a, c2.a, quad.x), mix(c1.a, c3.a, quad.x), quad.y)*/;
	factor += c.rgb /*mix(mix(c0.rgb, c2.rgb, quad.x), mix(c1.rgb, c3.rgb, quad.x), quad.y)*/ * (vec3(1.0)-factor);
	color.rgb *= factor * mix(mix(ao.x, ao.z, quad.x), mix(ao.y, ao.w, quad.x), quad.y);
}