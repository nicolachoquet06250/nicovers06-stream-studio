#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform sampler2D uSampler;
uniform samplerExternalOES uObject;
uniform float uAlpha;

varying vec2 vTextureCoord;
varying vec2 vTextureObjectCoord;
varying vec2 vObjectSampleCoord;

void main() {
  vec4 sceneColor = texture2D(uSampler, vTextureCoord);
  if (vTextureObjectCoord.x < 0.0 || vTextureObjectCoord.x > 1.0 ||
      vTextureObjectCoord.y < 0.0 || vTextureObjectCoord.y > 1.0) {
    gl_FragColor = sceneColor;
  } else {
    vec4 mediaColor = texture2D(uObject, vObjectSampleCoord);
    gl_FragColor = mix(sceneColor, mediaColor, mediaColor.a * uAlpha);
  }
}
