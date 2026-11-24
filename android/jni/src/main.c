/* DX Garden for Android, native side. A0: start up, say which build this
   is, and draw a plain screen. */
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

int main(int argc, char *argv[])
{
    SDL_Window *win;
    SDL_Renderer *ren;
    int running = 1;

    (void)argc;
    (void)argv;
    SDL_Log("DX Garden %s build %s starting (SDL %d.%d.%d)", DXG_VERSION, DXG_BUILD,
            SDL_VERSIONNUM_MAJOR(SDL_GetVersion()), SDL_VERSIONNUM_MINOR(SDL_GetVersion()),
            SDL_VERSIONNUM_MICRO(SDL_GetVersion()));
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS)) {
        SDL_Log("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    /* Not SDL_WINDOW_FULLSCREEN: that hides the system bars (immersive mode).
       The app is edge to edge instead, with the bars drawn over it. */
    win = SDL_CreateWindow("DX Garden", 0, 0, 0);
    ren = win ? SDL_CreateRenderer(win, NULL) : NULL;
    if (!ren) {
        SDL_Log("window or renderer failed: %s", SDL_GetError());
        SDL_Quit();
        return 1;
    }
    SDL_Log("renderer: %s", SDL_GetRendererName(ren));
    while (running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_EVENT_QUIT)
                running = 0;
        }
        /* DX Garden's night-sky blue, so it's clearly our screen. */
        SDL_SetRenderDrawColor(ren, 0x0b, 0x1e, 0x3c, 0xff);
        SDL_RenderClear(ren);
        SDL_RenderPresent(ren);
    }
    SDL_DestroyRenderer(ren);
    SDL_DestroyWindow(win);
    SDL_Quit();
    return 0;
}
