import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { createAuthClient } from "./auth.mjs";
import { listScreen, mineScreen, readScreen, screenHash } from "./navigation.mjs";
import "./styles.css";

const apiOrigin = "http://localhost:8080";
const auth = createAuthClient(apiOrigin);
const signupPath = "/api/v1/members/signup";
const profilePath = "/api/v1/members/me";
const eventsPath = "/api/v1/running-events";
const minePath = "/api/v1/members/me/running-events";
const listStatuses = {
    "": "전체", RECRUITING: "모집 중", CLOSED: "모집 마감", CANCELED: "개최 취소", COMPLETED: "종료"
};
const groups = {
    created: "만든 모임", participating: "참가", waiting: "대기", ended: "취소·대기 마감"
};
const endReasons = {
    PARTICIPATION_CANCELED: "참가 취소", WAITLIST_CANCELED: "대기 취소",
    DIRECT_CANCELED: "직접 취소", EVENT_CANCELED: "개최 취소", WAITLIST_CLOSED: "대기 마감"
};

async function requestJson(path, method = "GET", body) {
    const options = { method };
    if (body !== undefined) {
        options.headers = { "Content-Type": "application/json" };
        options.body = JSON.stringify(body);
    }
    const signingUp = path === signupPath;
    const response = signingUp
        ? await fetch(new URL(path, apiOrigin), { ...options, credentials: "omit", redirect: "error" })
        : await auth.authFetch(path, options);
    if (response.status === 401 && !signingUp) throw new Error("SESSION_EXPIRED");
    if (!response.ok) {
        const error = await response.json().catch(() => null);
        throw new Error(error?.message || "요청을 처리하지 못했습니다.");
    }
    return response.json();
}

function pace(seconds) {
    return `${Math.floor(seconds / 60)}′${String(seconds % 60).padStart(2, "0")}″ /km`;
}

function dateTime(startAt) {
    return startAt.slice(0, 16).replace("T", " ");
}

function recruitment(event) {
    if (event.status !== "RECRUITING") {
        return { label: { CANCELED: "개최 취소", CLOSED: "모집 마감", COMPLETED: "종료" }[event.status] ?? event.status, tone: "end" };
    }
    if (event.currentParticipantCount < event.capacity) return { label: "모집 중", tone: "" };
    const waitOpen = Date.parse(event.startAt) - Date.now() > 10 * 60 * 1000;
    return waitOpen ? { label: "대기 가능", tone: "wait" } : { label: "대기 마감", tone: "end" };
}

function participationAction(event) {
    if (event.isCreator || event.status !== "RECRUITING") return;
    const minutesLeft = (Date.parse(event.startAt) - Date.now()) / 60_000;
    if (event.applicationStatus === "PARTICIPATING") {
        if (minutesLeft > 10) return ["참가 취소", "participants/cancel", "POST", () => "참가 취소 완료"];
        return;
    }
    if (event.applicationStatus === "WAITING") {
        if (minutesLeft > 10) return ["대기 취소", "waitlist/me", "DELETE", () => "대기 취소 완료"];
        return;
    }
    const full = event.currentParticipantCount >= event.capacity;
    if (minutesLeft <= (full ? 10 : 0)) return;
    return [full ? "대기 신청" : "참가 신청", "participants", "POST", result =>
        result.status === "WAITING" ? `대기 ${result.position}번 등록 완료` : "참가 확정"];
}

function applicationLabel(application) {
    return {
        PARTICIPATING: "참가 확정",
        WAITING: `대기 ${application.waitlistPosition}번`,
        ENDED: endReasons[application.endReason]
    }[application.applicationStatus];
}

function errorMessage(error) {
    return error instanceof TypeError ? "서버에 연결할 수 없습니다. 연결 상태를 확인해 주세요." : error.message;
}

function Hero() {
    return <section className="hero">
        <div>
            <p className="eyebrow">RUN TOGETHER</p>
            <h1>오늘도 같이<br />한 바퀴!</h1>
            <p>나와 페이스가 맞는 러닝 메이트를 찾아보세요.</p>
        </div>
        <div className="art" aria-hidden="true">
            <div className="track" /><div className="runner">🏃</div><span className="spark">✦</span>
        </div>
    </section>;
}

function Notice({ message, tone = "info", className = "" }) {
    return <p className={`notice notice-${tone} ${className}`} role={tone === "error" ? "alert" : "status"}>
        {message && <><span className="notice-icon" aria-hidden="true">{tone === "error" ? "!" : "i"}</span>{message}</>}
    </p>;
}

function LoginForm({ busy, email, notice, onLogin, onSignup }) {
    async function submit(event) {
        event.preventDefault();
        const form = event.currentTarget;
        const values = new FormData(form);
        await onLogin(values.get("email"), values.get("password"));
        form.elements.password.value = "";
    }

    return <form className="box" onSubmit={submit}>
        <h2>로그인</h2>
        <label>이메일 <input name="email" type="email" autoComplete="username" defaultValue={email} required /></label>
        <label>비밀번호 <input name="password" type="password" autoComplete="current-password" minLength={8} maxLength={100} required /></label>
        <Notice {...notice} />
        <button className="action" disabled={busy}>로그인</button>
        <button type="button" className="form-link" disabled={busy} onClick={onSignup}>회원가입</button>
    </form>;
}

function newPasswordError(password) {
    if (!password.trim() || password.length < 8) return "비밀번호는 8자 이상 입력해 주세요.";
    if (new TextEncoder().encode(password).length > 71) return "비밀번호가 너무 깁니다. 길이를 줄여 주세요.";
    return "";
}

function NewPasswordField({ name, label, required = false }) {
    const helpId = `${name}-help`;
    return <label>{label}
        <input name={name} type="password" autoComplete="new-password" aria-describedby={helpId} required={required} />
        <span id={helpId} className="field-help">8자 이상</span>
    </label>;
}

function SignupForm({ busy, notice, onSignup, onBack }) {
    const [validation, setValidation] = useState("");

    function submit(event) {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        const profile = Object.fromEntries(["email", "name", "nickname", "region"]
            .map(name => [name, fields.get(name).trim()]));
        const password = fields.get("password");
        const passwordError = newPasswordError(password);
        const averagePaceSeconds = Number(fields.get("paceMinutes")) * 60 + Number(fields.get("paceSeconds"));
        if (Object.values(profile).some(value => !value)) {
            setValidation("필수 항목을 모두 입력해 주세요.");
        } else if (passwordError) {
            setValidation(passwordError);
        } else if (averagePaceSeconds < 180 || averagePaceSeconds > 900) {
            setValidation("평균 페이스는 3:00~15:00/km 범위로 입력해 주세요.");
        } else {
            setValidation("");
            onSignup({ ...profile, password, averagePaceSeconds, preferredDistanceKm: Number(fields.get("preferredDistanceKm")) });
        }
    }

    return <form className="box event-form" onSubmit={submit}>
        <h2>회원가입</h2>
        <label>이메일 <input name="email" type="email" autoComplete="email" required />
            <span className="field-help">이메일 형식 · 중복 불가</span>
        </label>
        <NewPasswordField name="password" label="비밀번호" required />
        <label>이름 <input name="name" autoComplete="name" maxLength={30} required /><span className="field-help">최대 30자</span></label>
        <label>닉네임 <input name="nickname" autoComplete="nickname" maxLength={30} required />
            <span className="field-help">최대 30자 · 중복 불가</span>
        </label>
        <label>지역 <input name="region" maxLength={50} required /><span className="field-help">최대 50자</span></label>
        <fieldset className="pace-fields">
            <legend>평균 페이스 (1km 기준)</legend>
            <label>분 <input name="paceMinutes" type="number" min={3} max={15} step={1} placeholder="6" required /></label>
            <label>초 <input name="paceSeconds" type="number" min={0} max={59} step={1} defaultValue={0} required /></label>
            <p className="field-help pace-help">3:00~15:00/km</p>
        </fieldset>
        <label>선호 거리 (km) <input name="preferredDistanceKm" type="number" min={1} max={100} step="any" required />
            <span className="field-help">1~100km</span>
        </label>
        <Notice message={validation || notice.message} tone={validation ? "error" : notice.tone} />
        <button className="action" disabled={busy}>{busy ? "가입 중…" : "가입하기"}</button>
        <button type="button" className="form-link" disabled={busy} onClick={onBack}>로그인으로 돌아가기</button>
    </form>;
}

function Badge({ label, tone = "" }) {
    return <span className={`badge ${tone}`}>{label}</span>;
}

function Pagination({ page, onPage }) {
    if (page.totalPages <= 1) return null;
    return <div className="pagination">
        <button disabled={page.first} onClick={() => onPage(page.number - 1)}>이전</button>
        <span>{page.number + 1} / {page.totalPages}</span>
        <button disabled={page.last} onClick={() => onPage(page.number + 1)}>다음</button>
    </div>;
}

function paceSeconds(value) {
    const [minutes, seconds] = value.split(":").map(Number);
    return minutes * 60 + seconds;
}

function listQuery({ page, filters, status }) {
    const params = new URLSearchParams({ page, size: 9 });
    if (status) params.set("status", status);
    for (const key of ["keyword", "region", "minDistance", "maxDistance"]) {
        if (filters[key]) params.set(key, filters[key]);
    }
    for (const key of ["minPace", "maxPace"]) {
        if (filters[key]) params.set(key, paceSeconds(filters[key]));
    }
    return params.toString();
}

function EventFilters({ filters, onFilter }) {
    const [validation, setValidation] = useState("");

    function submit(event) {
        event.preventDefault();
        const values = new FormData(event.currentTarget);
        const next = Object.fromEntries(Array.from(values, ([key, value]) => [key, value.trim()]));
        const minPace = next.minPace ? paceSeconds(next.minPace) : null;
        const maxPace = next.maxPace ? paceSeconds(next.maxPace) : null;
        if ([minPace, maxPace].some(value => value !== null && (value <= 0 || value > 2147483647))) {
            setValidation("페이스를 올바르게 입력해 주세요.");
        } else if (minPace !== null && maxPace !== null && minPace >= maxPace) {
            setValidation("페이스 상한은 하한보다 커야 합니다.");
        } else if (next.minDistance && next.maxDistance && Number(next.minDistance) >= Number(next.maxDistance)) {
            setValidation("거리 상한은 하한보다 커야 합니다.");
        } else {
            setValidation("");
            onFilter(next);
        }
    }

    return <form className="box event-filters" onSubmit={submit}>
        <label>이벤트 검색
            <input type="search" name="keyword" defaultValue={filters.keyword} placeholder="제목, 소개, 러닝 장소" />
        </label>
        <label>지역
            <input name="region" defaultValue={filters.region} placeholder="예: 서울" />
        </label>
        <fieldset className="filter-range">
            <legend>목표 페이스 (분:초/km)</legend>
            <label>이상 <input name="minPace" pattern="[0-9]+:[0-5][0-9]" defaultValue={filters.minPace} placeholder="5:45" /></label>
            <label>미만 <input name="maxPace" pattern="[0-9]+:[0-5][0-9]" defaultValue={filters.maxPace} placeholder="6:20" /></label>
        </fieldset>
        <fieldset className="filter-range">
            <legend>목표 거리 (km)</legend>
            <label>이상 <input name="minDistance" type="number" min={0.1} step="any" defaultValue={filters.minDistance} placeholder="4.5" /></label>
            <label>미만 <input name="maxDistance" type="number" min={0.1} step="any" defaultValue={filters.maxDistance} placeholder="7.5" /></label>
        </fieldset>
        <div className="filter-buttons">
            <button className="action">검색·필터 적용</button>
            <button type="button" onClick={event => {
                event.currentTarget.form.reset();
                setValidation("");
                onFilter({});
            }}>초기화</button>
        </div>
        <Notice message={validation} tone="error" className="filter-validation" />
    </form>;
}

function EventList({ page, filters, status, onFilter, onStatus, onPage, onDetail, onCreate }) {
    return <>
        <div className="section-head">
            <h2>러닝 모임</h2>
            <button className="action" onClick={onCreate}>모임 만들기</button>
        </div>
        <EventFilters key={JSON.stringify(filters)} filters={filters} onFilter={onFilter} />
        <div className="chips status-filters" role="group" aria-label="모집 상태 필터">
            {Object.entries(listStatuses).map(([value, label]) => <button key={value} type="button"
                aria-pressed={value === status} onClick={() => onStatus(value)}>{label}</button>)}
        </div>
        <p className="sub list-order">생성 최신순{page && ` · ${page.totalElements}개`}</p>
        {page && <>
            {page.content.length ? <div className="grid">
                {page.content.map((event, index) => <article className="event" key={event.id}>
                    <div className="cover" aria-hidden="true">{["🌇", "🌳", "☀️"][index % 3]}</div>
                    <div className="body">
                        <Badge {...recruitment(event)} />
                        <h3>{event.title}</h3>
                        <p className="sub">{dateTime(event.startAt)} · {event.runningPlace}</p>
                        <div className="event-tags">
                            <span>{event.region}</span>
                            <span>{pace(event.preferredPaceSeconds)}</span>
                            <span>{event.targetDistanceKm} km</span>
                        </div>
                        <p>{event.currentParticipantCount}/{event.capacity}명</p>
                        <button className="action" onClick={() => onDetail(event.id)}>상세 보기</button>
                    </div>
                </article>)}
            </div> : <p>{status || Object.values(filters).some(Boolean) ? "조건에 맞는 러닝 모임이 없습니다." : "등록된 러닝 모임이 없습니다."}</p>}
            <Pagination page={page} onPage={onPage} />
        </>}
    </>;
}

function EventForm({ initialEvent, notice, onSubmit, onBack, submitting }) {
    const [validation, setValidation] = useState("");
    const editing = Boolean(initialEvent);
    const detailsLocked = initialEvent?.currentParticipantCount > 1;
    const initialStart = initialEvent?.startAt.slice(0, 16);

    function submit(event) {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        const details = Object.fromEntries(["title", "description", "region", "runningPlace", "meetingPlace"]
            .map(name => {
                const value = fields.get(name);
                return [name, value === initialEvent?.[name] ? value : value.trim()];
            }));
        const enteredStart = fields.get("startAt");
        const startAt = enteredStart === initialStart ? initialEvent.startAt : enteredStart;
        const preferredPaceSeconds = Number(fields.get("paceMinutes")) * 60 + Number(fields.get("paceSeconds"));
        if (Object.values(details).some(value => !value.trim())) {
            setValidation("제목, 소개, 지역, 러닝 장소, 집합 장소를 입력해 주세요.");
        } else if (editing && Date.parse(startAt) <= Date.now() + 15 * 60_000) {
            setValidation("시작 일시는 현재 시각보다 15분 넘게 뒤여야 합니다.");
        } else if (Date.parse(startAt) <= Date.now()) {
            setValidation("시작 일시는 현재 시각 이후로 설정해 주세요.");
        } else if (preferredPaceSeconds <= 0 || preferredPaceSeconds > 2147483647) {
            setValidation("목표 페이스를 올바르게 입력해 주세요.");
        } else {
            setValidation("");
            onSubmit({
                ...details, startAt, preferredPaceSeconds,
                targetDistanceKm: Number(fields.get("targetDistanceKm")),
                capacity: Number(fields.get("capacity"))
            });
        }
    }

    return <>
        <button className="back" disabled={submitting} onClick={onBack}>{editing ? "← 모임 상세" : "← 러닝 목록"}</button>
        <form className="box event-form" onSubmit={submit}>
            <h2>{editing ? "러닝 모임 수정" : "러닝 모임 만들기"}</h2>
            {detailsLocked && <Notice message="참가자가 있어 시작 일시와 장소를 변경할 수 없습니다." />}
            <label>모임 제목 <input name="title" defaultValue={initialEvent?.title} maxLength={255} required /></label>
            <label>모임 소개 <textarea name="description" defaultValue={initialEvent?.description} rows={3} maxLength={255} required /></label>
            <label>지역 <input name="region" defaultValue={initialEvent?.region} readOnly={detailsLocked} maxLength={255} required /></label>
            <label>러닝 장소 <input name="runningPlace" defaultValue={initialEvent?.runningPlace} readOnly={detailsLocked} maxLength={255} required /></label>
            <label>집합 장소 <input name="meetingPlace" defaultValue={initialEvent?.meetingPlace} readOnly={detailsLocked} maxLength={255} required /></label>
            <label>시작 일시 <input name="startAt" type="datetime-local" defaultValue={initialStart} readOnly={detailsLocked} required /></label>
            <fieldset className="pace-fields">
                <legend>목표 페이스 (1km 기준)</legend>
                <label>분 <input name="paceMinutes" type="number" min={0} step={1} placeholder="6"
                    defaultValue={editing ? Math.floor(initialEvent.preferredPaceSeconds / 60) : undefined} required /></label>
                <label>초 <input name="paceSeconds" type="number" min={0} max={59} step={1}
                    defaultValue={(initialEvent?.preferredPaceSeconds ?? 0) % 60} required /></label>
            </fieldset>
            <label>목표 거리 (km) <input name="targetDistanceKm" type="number" min={0.1} step="any"
                defaultValue={initialEvent?.targetDistanceKm} required /></label>
            <label>정원 (주최자 포함) <input name="capacity" type="number" min={initialEvent?.currentParticipantCount ?? 1}
                max={2147483647} step={1} defaultValue={initialEvent?.capacity} required /></label>
            <Notice message={validation || notice.message} tone={validation ? "error" : notice.tone} />
            <button className="action" disabled={submitting}>{submitting ? "저장 중…" : editing ? "변경 사항 저장" : "모임 만들기"}</button>
        </form>
    </>;
}

function ProfileForm({ account, notice, onSave, onBack, saving }) {
    const [validation, setValidation] = useState("");

    function submit(event) {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        const details = Object.fromEntries(["name", "nickname", "region"].map(name => [name, fields.get(name).trim()]));
        const averagePaceSeconds = Number(fields.get("paceMinutes")) * 60 + Number(fields.get("paceSeconds"));
        const currentPassword = fields.get("currentPassword");
        const newPassword = fields.get("newPassword");
        const confirmation = fields.get("confirmation");
        const passwordError = newPassword ? newPasswordError(newPassword) : "";
        if (Object.values(details).some(value => !value)) {
            setValidation("이름, 닉네임, 지역을 입력해 주세요.");
        } else if (averagePaceSeconds < 180 || averagePaceSeconds > 900) {
            setValidation("평균 페이스는 3:00~15:00/km 범위로 입력해 주세요.");
        } else if ((currentPassword || newPassword || confirmation) && (!currentPassword.trim() || !newPassword)) {
            setValidation("현재 비밀번호와 새 비밀번호를 모두 입력해 주세요.");
        } else if (passwordError) {
            setValidation(passwordError);
        } else if (newPassword !== confirmation) {
            setValidation("새 비밀번호와 확인 값이 일치하지 않습니다.");
        } else {
            setValidation("");
            onSave({
                ...details, averagePaceSeconds, preferredDistanceKm: Number(fields.get("preferredDistanceKm")),
                ...(newPassword ? { currentPassword, newPassword } : {})
            });
        }
    }

    return <>
        <button type="button" className="back" disabled={saving} onClick={onBack}>← 마이페이지</button>
        <form className="box event-form" onSubmit={submit}>
            <h2>내 정보 수정</h2>
            <label>이메일 <input type="email" value={account.email} readOnly /></label>
            <label>이름 <input name="name" autoComplete="name" defaultValue={account.name} maxLength={30} required /></label>
            <label>닉네임 <input name="nickname" autoComplete="nickname" defaultValue={account.nickname} maxLength={30} required />
                <span className="field-help">최대 30자 · 중복 불가</span>
            </label>
            <label>지역 <input name="region" defaultValue={account.region} maxLength={50} required /></label>
            <fieldset className="pace-fields">
                <legend>평균 페이스 (1km 기준)</legend>
                <label>분 <input name="paceMinutes" type="number" min={3} max={15} step={1}
                    defaultValue={Math.floor(account.averagePaceSeconds / 60)} required /></label>
                <label>초 <input name="paceSeconds" type="number" min={0} max={59} step={1}
                    defaultValue={account.averagePaceSeconds % 60} required /></label>
                <p className="field-help pace-help">3:00~15:00/km</p>
            </fieldset>
            <label>선호 거리 (km) <input name="preferredDistanceKm" type="number" min={1} max={100} step="0.01"
                defaultValue={account.preferredDistanceKm} required /><span className="field-help">1~100km</span></label>
            <fieldset className="password-fields">
                <legend>비밀번호 변경 (선택)</legend>
                <label>현재 비밀번호 <input name="currentPassword" type="password" autoComplete="current-password"
                    minLength={8} maxLength={100} /></label>
                <NewPasswordField name="newPassword" label="새 비밀번호" />
                <label>새 비밀번호 확인 <input name="confirmation" type="password" autoComplete="new-password" /></label>
            </fieldset>
            <Notice message={validation || notice.message} tone={validation ? "error" : notice.tone} />
            <button className="action" disabled={saving}>{saving ? "저장 중…" : "변경 사항 저장"}</button>
        </form>
    </>;
}

function MyEvents({ account, group, page, onGroup, onPage, onDetail, onProfile }) {
    const [counts, setCounts] = useState({});
    const initialGroup = useRef(group);

    useEffect(() => {
        let active = true;
        for (const key of Object.keys(groups)) {
            if (key === initialGroup.current) continue;
            requestJson(`${minePath}/${key}?page=0&size=1`).then(result => {
                if (!active) return;
                setCounts(previous => Object.hasOwn(previous, key) ? previous : { ...previous, [key]: result.totalElements });
            }).catch(() => {
                // 다른 탭의 개수 조회 실패가 현재 목록을 막지 않도록 한다.
            });
        }
        return () => { active = false; };
    }, []);

    useEffect(() => {
        if (page) setCounts(previous => ({ ...previous, [group]: page.totalElements }));
    }, [group, page]);

    const profile = {
        닉네임: account.nickname,
        이름: account.name,
        이메일: account.email,
        지역: account.region,
        "평균 페이스": pace(account.averagePaceSeconds),
        "선호 거리": `${account.preferredDistanceKm} km`
    };

    return <>
        <div className="mine profile-heading">
            <div><p className="eyebrow">MY RUNS</p><h1>{account.nickname}의 러닝</h1></div>
            <span className="shoe-sticker" aria-hidden="true">👟</span>
        </div>
        <section className="box profile-summary" aria-labelledby="profile-summary-title">
            <div className="profile-summary-head">
                <h2 id="profile-summary-title">내 정보</h2>
                <button type="button" className="action" onClick={onProfile}>내 정보 수정</button>
            </div>
            <dl className="profile-facts">
                {Object.entries(profile).map(([label, value]) => <div key={label}>
                    <dt>{label}</dt><dd>{value}</dd>
                </div>)}
            </dl>
        </section>
        <div className="chips section-head mine-tabs" aria-label="내 러닝 분류" role="group">
            {Object.entries(groups).map(([key, label]) => {
                const count = key === group && page ? page.totalElements : counts[key];
                return <button key={key} aria-pressed={key === group} onClick={() => onGroup(key)}>
                    {label}<span className="tab-count">{count ?? "—"}</span>
                </button>;
            })}
        </div>
        {page && <>
            <div className="mine-list">
                {page.content.map(item => {
                    const event = item.event ?? item;
                    const status = recruitment(event);
                    const label = applicationLabel(item);
                    const completedParticipation = group === "participating" && event.status === "COMPLETED";
                    return <article className="mine-row" key={event.id}>
                        <div><h3>{event.title}</h3><p className="sub">{dateTime(event.startAt)} · {event.runningPlace}</p></div>
                        {completedParticipation
                            ? <Badge label="모임 종료" tone="end" />
                            : <Badge label={label ?? status.label} tone={label ? { ended: "end", waiting: "wait" }[group] : status.tone} />}
                        <button className="action" onClick={() => onDetail(event.id)}>상세 보기</button>
                    </article>;
                })}
                {!page.content.length && <p>이 분류에 해당하는 러닝 모임이 없습니다.</p>}
            </div>
            <Pagination page={page} onPage={onPage} />
        </>}
    </>;
}

function EventDetail({ event, notice, retry, onBack, onAction, onEdit, actionDisabled }) {
    const application = event.isCreator ? "내가 만든 모임" : applicationLabel(event);
    const action = participationAction(event);
    const manageableStatus = event.isCreator && ["RECRUITING", "CLOSED"].includes(event.status);
    const timeUntilStart = Date.parse(event.startAt) - Date.now();
    const canManage = manageableStatus && timeUntilStart > 15 * 60_000;
    const canComplete = event.isCreator && event.status === "CLOSED" && timeUntilStart <= 0;
    const facts = {
        지역: event.region, "러닝 장소": event.runningPlace, "집합 장소": event.meetingPlace,
        거리: `${event.targetDistanceKm} km`, "목표 페이스": pace(event.preferredPaceSeconds),
        주최자: event.creatorNickname
    };

    return <>
        <button className="back" onClick={onBack}>← 이전 화면</button>
        <div className="hero detail-hero"><h1>{event.title}</h1></div>
        <div className="detail">
            <div className="box">
                <h2>러닝 정보</h2>
                <p>{event.description}</p>
                <dl className="facts">
                    <div className="start-time">
                        <dt>시작 일시</dt>
                        <dd><time dateTime={event.startAt}>{dateTime(event.startAt)}</time></dd>
                    </div>
                    {Object.entries(facts).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
                </dl>
            </div>
            <div className="box">
                <Badge {...recruitment(event)} />
                <h2>{event.status === "CANCELED" ? "취소 당시 참가 인원" : "참가 인원"} {event.currentParticipantCount} / {event.capacity}명</h2>
                {event.participantNicknames?.length > 0 && <ul className="participant-list" aria-label="참가자 닉네임">
                    {event.participantNicknames.map(nickname => <li key={nickname}>{nickname}</li>)}
                </ul>}
                {application && <p>{application}</p>}
                <Notice {...notice} />
                {retry && <button className="action" onClick={retry}>다시 시도</button>}
                {action && <button className="action" disabled={actionDisabled} onClick={() => onAction(action)}>{action[0]}</button>}
                {canManage && <div className="management-actions">
                    <button type="button" className="action" disabled={actionDisabled} onClick={() => onEdit(event)}>모임 수정</button>
                    <button type="button" className="danger-action" disabled={actionDisabled} onClick={() => {
                        if (window.confirm("모임을 취소하시겠습니까? 참가 및 대기 신청도 종료됩니다.")) {
                            onAction(["개최 취소", "cancel", "POST", () => "모임 개최 취소 완료"]);
                        }
                    }}>개최 취소</button>
                </div>}
                {canComplete && <button type="button" className="action" disabled={actionDisabled} onClick={() => {
                    if (window.confirm("러닝을 마쳤나요? 완료 처리 후에는 되돌릴 수 없습니다.")) {
                        onAction(["모임 완료", "complete", "POST", () => "모임 완료"]);
                    }
                }}>모임 완료</button>}
                {manageableStatus && !canManage && !canComplete &&
                    <Notice message="시작 15분 전부터 수정과 개최 취소가 제한됩니다." />}
            </div>
        </div>
    </>;
}

function currentScreen() {
    return readScreen(window.location.hash);
}

function updateRoute(screen, mode = "push") {
    const hash = screenHash(screen);
    if (hash === window.location.hash) return;
    if (mode === "replace") window.history.replaceState(null, "", hash);
    else window.history.pushState(null, "", hash);
}

function App() {
    const [account, setAccount] = useState(null);
    const [authScreen, setAuthScreen] = useState(null);
    const [loginEmail, setLoginEmail] = useState("");
    const [screen, setScreen] = useState(currentScreen);
    const [data, setData] = useState(null);
    const [notice, updateNotice] = useState({ message: "로그인 상태 확인 중", tone: "info" });
    const [successNotice, setSuccessNotice] = useState("");
    const [retry, setRetry] = useState(null);
    const [busy, setBusy] = useState(false);
    const [actionDisabled, setActionDisabled] = useState(false);
    const [saving, setSaving] = useState(false);
    const currentView = useRef(0);
    const pendingAuth = useRef(false);
    const pendingAction = useRef(false);

    function setNotice(message, tone = "info") {
        updateNotice({ message, tone });
    }

    function signedOut(message, tone) {
        currentView.current++;
        setAccount(null);
        setAuthScreen(currentScreen().type === "signup" ? "signup" : "login");
        setLoginEmail("");
        setScreen(listScreen);
        setData(null);
        setRetry(null);
        setSuccessNotice("");
        setNotice(message, tone);
    }

    function report(error, tryAgain) {
        if (error.message === "SESSION_EXPIRED") {
            signedOut("로그인 시간이 만료되었습니다. 다시 로그인해 주세요.", "error");
            return;
        }
        setNotice(errorMessage(error), "error");
        setRetry(() => tryAgain);
    }

    async function loadScreen(next, successMessage = "", historyMode = "push") {
        const request = ++currentView.current;
        if (historyMode !== "none") updateRoute(next, historyMode);
        setScreen(next);
        setData(null);
        setRetry(null);
        setActionDisabled(false);
        setSuccessNotice(successMessage);
        if (next.type === "create") {
            setNotice("");
            return;
        }
        setNotice("러닝 정보 불러오는 중");
        const query = `?page=${next.page}&size=9`;
        const path = {
            list: () => `${eventsPath}?${listQuery(next)}`,
            mine: () => `${minePath}/${next.group}${query}`,
            profile: () => profilePath,
            detail: () => `${eventsPath}/${next.id}`,
            edit: () => `${eventsPath}/${next.id}`
        }[next.type]();
        try {
            const result = await requestJson(path);
            if (request !== currentView.current) return;
            if (next.type === "edit" && (!result.isCreator || !["RECRUITING", "CLOSED"].includes(result.status)
                || Date.parse(result.startAt) - Date.now() <= 15 * 60_000)) {
                updateRoute(next.back, "replace");
                setScreen(next.back);
                setData(result);
                setNotice("이 모임을 수정할 수 없습니다.", "error");
                return;
            }
            setData(result);
            setNotice("");
        } catch (error) {
            if (request === currentView.current) report(error, () => loadScreen(next));
        }
    }

    async function enter() {
        const member = await requestJson(profilePath);
        setAccount(member);
        setAuthScreen(null);
        const next = currentScreen();
        await loadScreen(next.type === "signup" ? listScreen : next, "", "replace");
    }

    async function runAuth(startMessage, work, retryAuth = null) {
        if (pendingAuth.current) return;
        pendingAuth.current = true;
        setBusy(true);
        setSuccessNotice("");
        setNotice(startMessage);
        setRetry(null);
        try {
            await work();
        } catch (error) {
            report(error, retryAuth);
        } finally {
            pendingAuth.current = false;
            setBusy(false);
        }
    }

    function restore() {
        return runAuth("로그인 상태 확인 중", async () => {
            if (await auth.restoreLogin()) await enter();
            else signedOut("러닝 모임을 보려면 로그인해 주세요.");
        }, restore);
    }

    function login(email, password) {
        return runAuth("로그인 중", async () => {
            await auth.login(email, password);
            await enter();
        });
    }

    function openAuthScreen(next, historyMode = "push") {
        updateRoute(next === "signup" ? { type: "signup" } : listScreen, historyMode);
        setAuthScreen(next);
        setNotice("");
        setRetry(null);
        setSuccessNotice("");
    }

    function signup(body) {
        return runAuth("회원가입 중", async () => {
            await requestJson(signupPath, "POST", body);
            openAuthScreen("login", "replace");
            setLoginEmail(body.email);
            setNotice("가입한 이메일로 로그인해 주세요.");
            setSuccessNotice("회원가입 완료");
        });
    }

    function logout() {
        return runAuth("로그아웃 중", async () => {
            await auth.logout();
            updateRoute(listScreen, "replace");
            signedOut("");
            setSuccessNotice("로그아웃 완료");
        }, logout);
    }

    useEffect(() => { restore(); }, []);

    useEffect(() => {
        function handlePopState() {
            const next = currentScreen();
            if (account) {
                if (next.type === "signup") loadScreen(listScreen, "", "replace");
                else loadScreen(next, "", "none");
            } else if (authScreen) {
                setAuthScreen(next.type === "signup" ? "signup" : "login");
                setNotice("");
                setSuccessNotice("");
                setRetry(null);
            }
        }
        window.addEventListener("popstate", handlePopState);
        return () => window.removeEventListener("popstate", handlePopState);
    }, [account, authScreen]);

    async function applyAction([, suffix, method, success]) {
        if (pendingAction.current) return;
        const request = currentView.current;
        pendingAction.current = true;
        setActionDisabled(true);
        setSuccessNotice("");
        setNotice("모임 상태 변경 중");
        try {
            const result = await requestJson(`${eventsPath}/${screen.id}/${suffix}`, method);
            if (request === currentView.current) await loadScreen(screen, success(result));
        } catch (error) {
            if (request !== currentView.current) return;
            const uncertain = error instanceof TypeError;
            report(error, uncertain ? () => loadScreen(screen) : null);
            if (!uncertain) setActionDisabled(false);
        } finally {
            pendingAction.current = false;
        }
    }

    const openDetail = id => loadScreen({ type: "detail", id, back: screen });
    const changePage = page => loadScreen({ ...screen, page });

    function openEventForm(initialEvent) {
        loadScreen(initialEvent ? { type: "edit", id: initialEvent.id, back: screen } : { type: "create", back: screen });
    }

    async function saveProfile(body) {
        if (pendingAction.current) return;
        const request = currentView.current;
        pendingAction.current = true;
        setSaving(true);
        setNotice("변경 사항 저장 중");
        try {
            const member = await requestJson(profilePath, "PUT", body);
            if (request !== currentView.current) return;
            setAccount(member);
            await loadScreen(screen.back, "내 정보 수정 완료", "replace");
        } catch (error) {
            if (request === currentView.current) report(error, null);
        } finally {
            pendingAction.current = false;
            setSaving(false);
        }
    }

    async function saveEvent(body) {
        if (pendingAction.current) return;
        const editing = screen.type === "edit";
        const request = currentView.current;
        pendingAction.current = true;
        setSaving(true);
        setNotice(editing ? "변경 사항 저장 중" : "러닝 모임 생성 중");
        try {
            const event = await requestJson(editing ? `${eventsPath}/${screen.id}` : eventsPath,
                editing ? "PUT" : "POST", body);
            if (request !== currentView.current) return;
            const detail = editing ? screen.back : { type: "detail", id: event.id, back: screen.back };
            updateRoute(detail, "replace");
            setScreen(detail);
            setData(event);
            setNotice("");
            setSuccessNotice(editing ? "러닝 모임 수정 완료" : "러닝 모임 생성 완료");
        } catch (error) {
            if (request !== currentView.current) return;
            if (error instanceof TypeError) {
                setNotice(editing ? "서버 응답이 없습니다. 다시 저장하기 전에 모임 상세를 확인해 주세요."
                    : "서버 응답이 없습니다. 다시 제출하기 전에 마이페이지의 만든 모임을 확인해 주세요.", "error");
            } else report(error, null);
        } finally {
            pendingAction.current = false;
            setSaving(false);
        }
    }

    const eventFormOpen = account && (screen.type === "create" || (screen.type === "edit" && data));
    const profileOpen = account && screen.type === "profile" && data;
    const detailOpen = data && screen.type === "detail";

    return <>
        <header>
            <div className="logo">Run<span>Mate</span> ✦</div>
            {account && <nav aria-label="화면 이동">
                <button disabled={saving} aria-pressed={screen.type === "list"} onClick={() => loadScreen(listScreen)}>러닝 찾기</button>
                <button disabled={saving} aria-pressed={screen.type === "mine" || screen.type === "profile"}
                    onClick={() => loadScreen(mineScreen)}>마이페이지</button>
                <button disabled={busy || saving} onClick={logout}>로그아웃</button>
            </nav>}
        </header>
        <div className={successNotice ? "success-toast is-visible" : "success-toast"}>
            <p role="status" aria-atomic="true">
                {successNotice && <><span className="success-icon" aria-hidden="true">✓</span>{successNotice}</>}
            </p>
            {successNotice && <button type="button" aria-label="성공 알림 닫기" onClick={() => setSuccessNotice("")}>×</button>}
        </div>
        <main>
            {screen.type === "list" && <Hero />}
            {!authScreen && !eventFormOpen && !profileOpen && !detailOpen && <Notice {...notice} className="screen-notice" />}
            {authScreen === "login" && <LoginForm busy={busy} email={loginEmail} notice={notice} onLogin={login}
                onSignup={() => openAuthScreen("signup")} />}
            {authScreen === "signup" && <SignupForm busy={busy} notice={notice} onSignup={signup} onBack={() => openAuthScreen("login")} />}
            {retry && !detailOpen && <button className="action" onClick={retry}>다시 시도</button>}
            <section aria-label="러닝 모임">
                {account && screen.type === "list" && <EventList page={data} filters={screen.filters} status={screen.status}
                    onFilter={filters => loadScreen({ ...screen, page: 0, filters })}
                    onStatus={status => loadScreen({ ...screen, page: 0, status })}
                    onPage={changePage} onDetail={openDetail} onCreate={() => openEventForm()} />}
                {eventFormOpen && <EventForm initialEvent={screen.type === "edit" ? data : undefined}
                    notice={notice} onSubmit={saveEvent} submitting={saving}
                    onBack={() => loadScreen(screen.back)} />}
                {profileOpen && <ProfileForm account={data} notice={notice} onSave={saveProfile} saving={saving}
                    onBack={() => loadScreen(screen.back)} />}
                {account && screen.type === "mine" && <MyEvents key={account.email} account={account} group={screen.group}
                    page={data} onGroup={group => loadScreen({ ...mineScreen, group })} onPage={changePage}
                    onDetail={openDetail} onProfile={() => loadScreen({ type: "profile", back: screen })} />}
                {detailOpen && <EventDetail event={data} notice={notice} retry={retry} onBack={() => loadScreen(screen.back)}
                    onAction={applyAction} onEdit={openEventForm} actionDisabled={actionDisabled || busy} />}
            </section>
        </main>
    </>;
}

createRoot(document.getElementById("root")).render(<App />);
