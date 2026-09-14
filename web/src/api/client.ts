/** Uniform API envelope returned by every backend endpoint. */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const BASE_URL = "/api/v1";

let tokenProvider: () => string | null = () => null;
let unauthorizedHandler: () => void = () => {};

/** Called once by the auth provider so requests carry the JWT and a 401 logs the user out. */
export function configureApiAuth(opts: { getToken: () => string | null; onUnauthorized: () => void }) {
  tokenProvider = opts.getToken;
  unauthorizedHandler = opts.onUnauthorized;
}

async function request<T>(path: string, init: RequestInit, isForm = false): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !isForm) headers.set("Content-Type", "application/json");
  const token = tokenProvider();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Check your connection and try again.");
  }

  let body: ApiResponse<T> | undefined;
  try {
    body = (await response.json()) as ApiResponse<T>;
  } catch {
    body = undefined;
  }

  if (response.status === 401 && !path.startsWith("/auth/login")) {
    unauthorizedHandler();
  }
  if (!response.ok) {
    // A non-JSON failure (a proxy error page, a rejected CORS request) leaves body undefined, and
    // HTTP/2 has no status text, so falling back to statusText yields "". The UI then renders an
    // empty error and the button looks like it did nothing. Always end up with something readable.
    const message =
      body?.message?.trim() ||
      response.statusText?.trim() ||
      `Request failed (HTTP ${response.status})`;
    throw new ApiError(body?.code ?? response.status, message);
  }
  if (!body) {
    throw new ApiError(response.status, "Empty response body");
  }
  return body.data;
}

export const api = {
  get: <T>(path: string) => request<T>(path, { method: "GET" }),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) => request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  upload: <T>(path: string, form: FormData) => request<T>(path, { method: "POST", body: form }, true),
};

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return "Something went wrong";
}

export function qs(params: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : "";
}
