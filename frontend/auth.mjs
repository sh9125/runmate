// 화면을 처음 열 때 restoreLogin()을 호출하면 갱신 쿠키로 로그인 상태를 복구한다.
export function createAuthClient(apiOrigin = "http://localhost:8080") {
    const origin = new URL(apiOrigin).origin;
    let accessToken = null;
    let pendingRefresh = null;
    let sessionVersion = 0;

    async function authRequest(action, body) {
        const headers = { "X-Runmate-Auth": "1" };
        if (body !== undefined) {
            headers["Content-Type"] = "application/json";
        }
        return fetch(new URL(`/api/v1/auth/${action}`, origin), {
            method: "POST",
            credentials: "include",
            redirect: "error",
            headers,
            body: body === undefined ? undefined : JSON.stringify(body)
        });
    }

    async function login(email, password) {
        const version = ++sessionVersion;
        const response = await authRequest("login", { email, password });
        if (!response.ok) {
            throw new Error(`로그인 실패 (${response.status})`);
        }
        const result = await response.json();
        if (version === sessionVersion) {
            sessionVersion++;
            accessToken = result.accessToken;
        }
    }

    function restoreLogin() {
        const version = sessionVersion;
        if (pendingRefresh?.version === version) {
            return pendingRefresh.promise;
        }
        const promise = (async () => {
            const response = await authRequest("refresh");
            if (response.status === 401) {
                if (version === sessionVersion) accessToken = null;
                return false;
            }
            if (!response.ok) {
                throw new Error(`로그인 복구 실패 (${response.status})`);
            }
            const result = await response.json();
            if (version !== sessionVersion) return false;
            accessToken = result.accessToken;
            return true;
        })().finally(() => {
            if (pendingRefresh?.promise === promise) pendingRefresh = null;
        });
        pendingRefresh = { version, promise };
        return promise;
    }

    async function logout() {
        const version = ++sessionVersion;
        const response = await authRequest("logout");
        if (!response.ok) {
            throw new Error(`로그아웃 실패 (${response.status})`);
        }
        if (version === sessionVersion) {
            sessionVersion++;
            accessToken = null;
        }
    }

    async function authFetch(path, init) {
        const url = new URL(path, origin);
        if (url.origin !== origin || !url.pathname.startsWith("/api/")) {
            throw new TypeError("RunMate API 경로를 지정해야 합니다.");
        }
        const request = new Request(url, init);
        const version = sessionVersion;
        const token = accessToken;

        function send(bearer) {
            const headers = new Headers(request.headers);
            headers.delete("Authorization");
            if (bearer) headers.set("Authorization", `Bearer ${bearer}`);
            return fetch(request.clone(), { headers, credentials: "omit", redirect: "error" });
        }

        const response = await send(token);
        if (response.status !== 401 || version !== sessionVersion) {
            return response;
        }
        // 이미 다른 요청에서 토큰을 갱신했다면 그 값을 재사용한다.
        if ((accessToken && accessToken !== token) || await restoreLogin()) {
            if (version === sessionVersion && accessToken) return send(accessToken);
        }
        return response;
    }

    return { login, restoreLogin, logout, authFetch };
}
