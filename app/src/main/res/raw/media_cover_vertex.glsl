attribute vec4 aPosition;
attribute vec4 aTextureCoord;
attribute vec4 aTextureObjectCoord;

uniform mat4 uObjectMatrix;

varying vec2 vTextureCoord;
varying vec2 vTextureObjectCoord;
varying vec2 vObjectSampleCoord;

void main() {
  gl_Position = aPosition;
  vTextureCoord = aTextureCoord.xy;
  vTextureObjectCoord = aTextureObjectCoord.xy;
  vObjectSampleCoord = (uObjectMatrix * aTextureObjectCoord).xy;
}
