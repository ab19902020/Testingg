import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { tex } from './textures.js';

// Sky dome with a sun glow, plus soft billboard clouds. Two moods: day and sunset.
const MOODS = {
  day: { top: 0x1f6fd0, mid: 0x5fb2ee, horizon: 0xd2ecff, ground: 0xbcd8e8, sun: 0xfff2d6, fog: 0xcfe6f7, sunDir: [0.45, 0.62, 0.64] },
  sunset: { top: 0x2b2160, mid: 0xb4517a, horizon: 0xffb37a, ground: 0xe9a27e, sun: 0xffd29a, fog: 0xf0b48c, sunDir: [-0.35, 0.18, -0.92] },
};

export class Sky {
  constructor(scene, renderer) {
    this.uniforms = {
      top: { value: new THREE.Color() },
      mid: { value: new THREE.Color() },
      horizon: { value: new THREE.Color() },
      groundCol: { value: new THREE.Color() },
      sunCol: { value: new THREE.Color() },
      sunDir: { value: new THREE.Vector3() },
    };
    const mat = new THREE.ShaderMaterial({
      uniforms: this.uniforms,
      side: THREE.BackSide,
      depthWrite: false,
      fog: false,
      toneMapped: false,
      vertexShader: /* glsl */ `
        varying vec3 vDir;
        void main() {
          vDir = normalize(position);
          vec4 p = modelViewMatrix * vec4(position, 1.0);
          gl_Position = projectionMatrix * p;
          gl_Position.z = gl_Position.w; // always behind everything
        }`,
      fragmentShader: /* glsl */ `
        uniform vec3 top, mid, horizon, groundCol, sunCol, sunDir;
        varying vec3 vDir;
        void main() {
          float y = vDir.y;
          vec3 c = y > 0.0
            ? mix(mix(horizon, mid, smoothstep(0.0, 0.25, y)), top, smoothstep(0.2, 0.9, y))
            : mix(horizon, groundCol, smoothstep(0.0, -0.2, y));
          float s = max(dot(normalize(vDir), normalize(sunDir)), 0.0);
          c += sunCol * (pow(s, 600.0) * 2.5 + pow(s, 12.0) * 0.35 + pow(s, 3.0) * 0.12);
          gl_FragColor = vec4(c, 1.0);
          #include <colorspace_fragment>
        }`,
    });
    this.dome = new THREE.Mesh(new THREE.SphereGeometry(450, 32, 16), mat);
    this.dome.frustumCulled = false;
    this.dome.renderOrder = -10;
    scene.add(this.dome);

    // clouds: clusters of soft sprites
    this.clouds = new THREE.Group();
    const cmat = new THREE.SpriteMaterial({ map: tex('cloud'), transparent: true, depthWrite: false, fog: false, opacity: 0.95 });
    this.cloudMat = cmat;
    for (let i = 0; i < 14; i++) {
      const g = new THREE.Group();
      const a = (i / 14) * Math.PI * 2 + Math.random() * 0.3;
      const r = 180 + Math.random() * 120;
      g.position.set(Math.cos(a) * r, 55 + Math.random() * 45, Math.sin(a) * r - 60);
      const n = 5 + Math.floor(Math.random() * 5);
      for (let k = 0; k < n; k++) {
        const s = new THREE.Sprite(cmat);
        const sz = 22 + Math.random() * 26;
        s.scale.set(sz * 1.6, sz, 1);
        s.position.set((k - n / 2) * 11 + Math.random() * 6, Math.random() * 8 - (k % 2) * 4, Math.random() * 10);
        g.add(s);
      }
      g.userData.speed = 0.6 + Math.random() * 0.8;
      this.clouds.add(g);
    }
    scene.add(this.clouds);

    // image-based lighting gives the glossy, toy-like look on every material
    const pmrem = new THREE.PMREMGenerator(renderer);
    this.env = pmrem.fromScene(new RoomEnvironment(renderer), 0.04).texture;
    scene.environment = this.env;
    if ('environmentIntensity' in scene) scene.environmentIntensity = 0.6;
    pmrem.dispose();
    this.set('day');
  }

  set(name) {
    const m = MOODS[name];
    this.mood = m;
    const u = this.uniforms;
    u.top.value.set(m.top);
    u.mid.value.set(m.mid);
    u.horizon.value.set(m.horizon);
    u.groundCol.value.set(m.ground);
    u.sunCol.value.set(m.sun);
    u.sunDir.value.set(...m.sunDir).normalize();
    this.cloudMat.color.set(name === 'sunset' ? 0xffc6b0 : 0xffffff);
  }

  update(dt, camera) {
    this.dome.position.copy(camera.position);
    for (const g of this.clouds.children) {
      g.position.x += g.userData.speed * dt;
      if (g.position.x > 320) g.position.x = -320;
    }
    this.clouds.position.set(camera.position.x * 0.9, 0, camera.position.z * 0.9);
  }
}
