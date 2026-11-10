export const listScreen = { type: "list", page: 0, filters: {}, status: "" };
export const mineScreen = { type: "mine", group: "participating", page: 0 };

const filterKeys = ["keyword", "region", "minPace", "maxPace", "minDistance", "maxDistance"];
const statuses = new Set(["RECRUITING", "CLOSED", "CANCELED", "COMPLETED"]);
const groups = new Set(["created", "participating", "waiting", "ended"]);

function splitRoute(hash) {
    const [path, query] = hash.replace(/^#/, "").split("?", 2);
    return { path, params: new URLSearchParams(query) };
}

function readListScreen(path, params) {
    const pageNumber = Number(params.get("page"));
    const page = Number.isInteger(pageNumber) && pageNumber >= 0 && pageNumber <= 2147483647 ? pageNumber : 0;
    if (path === "/mine") {
        const group = params.get("group");
        return { ...mineScreen, page, group: groups.has(group) ? group : mineScreen.group };
    }
    const status = params.get("status");
    const filters = Object.fromEntries(filterKeys.filter(key => params.get(key)).map(key => [key, params.get(key)]));
    return { ...listScreen, page, status: statuses.has(status) ? status : "", filters };
}

export function readScreen(hash) {
    const { path, params } = splitRoute(hash);
    if (path === "/signup") return { type: "signup" };
    if (path === "/mine" || path === "/events") return readListScreen(path, params);

    const origin = splitRoute(params.get("from") ?? "");
    const back = readListScreen(origin.path, origin.params);
    if (path === "/mine/profile") return { type: "profile", back: back.type === "mine" ? back : mineScreen };
    if (path === "/events/new") return { type: "create", back };

    const match = /^\/events\/([1-9][0-9]{0,18})(\/edit)?$/.exec(path);
    if (!match) return listScreen;
    const detail = { type: "detail", id: match[1], back };
    return match[2] ? { type: "edit", id: detail.id, back: detail } : detail;
}

export function screenHash(screen) {
    const params = new URLSearchParams();
    let path = "/events";
    switch (screen.type) {
        case "list":
            if (screen.status) params.set("status", screen.status);
            for (const key of filterKeys) {
                if (screen.filters[key]) params.set(key, screen.filters[key]);
            }
            break;
        case "mine":
            path = "/mine";
            if (screen.group !== mineScreen.group) params.set("group", screen.group);
            break;
        case "profile": {
            path = "/mine/profile";
            const origin = screenHash(screen.back ?? mineScreen);
            if (origin !== "#/mine") params.set("from", origin.slice(1));
            break;
        }
        case "detail":
        case "edit":
        case "create": {
            path = screen.type === "create" ? "/events/new" : `/events/${screen.id}`;
            const back = screen.type === "edit" ? screen.back.back : screen.back;
            if (screen.type === "edit") path += "/edit";
            const origin = screenHash(back ?? listScreen);
            if (origin !== "#/events") params.set("from", origin.slice(1));
            break;
        }
        case "signup":
            path = "/signup";
            break;
    }
    if (screen.page > 0) params.set("page", screen.page);
    const query = params.toString();
    return `#${path}${query ? `?${query}` : ""}`;
}
