# Metallum

Metallum is a Metal powered rendering backend for Minecraft on macOS, aiming to improve performance and efficiency on Apple Silicon systems. It builds upon the existing OpenGL/Vulkan backend abstraction. This project also serves as a demonstration of what a native Metal backend could look like, with the hope that Mojang may eventually decide to implement a native rendering path for macOS.

# Perfomance

Measured framerate on Apple M4 under the same scene and settings: 2K, 16 chunks, seed 1

| API    | Average FPS |
|--------|-------------|
| Metal  | 340         |
| Vulkan | 235         |
| OpenGL | 224         |