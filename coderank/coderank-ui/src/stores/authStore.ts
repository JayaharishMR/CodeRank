import { create } from 'zustand';

interface AuthState {
  authenticated: boolean;
  username: string | null;
  email: string | null;
  setAuth: (authenticated: boolean, username?: string, email?: string) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  authenticated: false,
  username: null,
  email: null,
  setAuth: (authenticated, username, email) =>
    set({ authenticated, username: username ?? null, email: email ?? null }),
  logout: () => set({ authenticated: false, username: null, email: null }),
}));
