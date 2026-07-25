import { useEffect, useMemo, useRef, useState } from 'react';
import { env, useEdgeHealth } from './integration';
import { appPlatform, platformCapabilities, platformDisplayName } from './platform/appPlatform';

type View = 'overview' | 'reports' | 'data' | 'activity' | 'settings';
type ReportStatus = '완료' | '검토 중' | '초안';
type Theme = 'light' | 'dark';
type FontSize = 'small' | 'medium' | 'large';

type Report = {
  id: string;
  title: string;
  description: string;
  category: string;
  owner: string;
  status: ReportStatus;
  progress: number;
  updated: string;
  tags: string[];
};

const navigation: { key: View; label: string; caption: string; icon: string }[] = [
  { key: 'overview', label: 'Overview', caption: '전체 현황', icon: '◫' },
  { key: 'reports', label: 'Reports', caption: '보고서 관리', icon: '▤' },
  { key: 'data', label: 'Data Map', caption: '데이터 연결', icon: '⌘' },
  { key: 'activity', label: 'Activity', caption: '활동 기록', icon: '◷' },
  { key: 'settings', label: 'Settings', caption: '환경 설정', icon: '⚙' },
];

const reports: Report[] = [
  { id: 'RPT-024', title: '2026 제품 운영 리포트', description: '핵심 제품 지표와 운영 상태를 한눈에 정리한 월간 보고서입니다.', category: 'Operations', owner: '김한별', status: '완료', progress: 100, updated: '오늘 09:42', tags: ['운영', 'KPI', '월간'] },
  { id: 'RPT-023', title: '고객 경험 개선안', description: '사용자 피드백과 행동 데이터를 기반으로 개선 과제를 정리했습니다.', category: 'Experience', owner: '이서윤', status: '검토 중', progress: 78, updated: '어제 17:20', tags: ['UX', '고객', '분석'] },
  { id: 'RPT-022', title: '백엔드 연동 명세', description: '프론트엔드 데이터 슬롯과 API 응답 계약을 정의합니다.', category: 'Engineering', owner: '박지호', status: '검토 중', progress: 64, updated: '7월 19일', tags: ['API', 'Contract'] },
  { id: 'RPT-021', title: '3분기 캠페인 성과', description: '채널별 유입과 전환 흐름을 비교한 캠페인 중간 보고서입니다.', category: 'Marketing', owner: '최유진', status: '초안', progress: 36, updated: '7월 18일', tags: ['캠페인', '전환'] },
  { id: 'RPT-020', title: '디자인 시스템 점검', description: '컴포넌트 일관성과 접근성 기준의 적용 상태를 점검했습니다.', category: 'Design', owner: '한지민', status: '완료', progress: 100, updated: '7월 16일', tags: ['UI', '접근성'] },
];

const activities = [
  { icon: '✓', tone: 'green', title: '제품 운영 리포트가 승인되었습니다', detail: '김한별 · RPT-024', time: '12분 전' },
  { icon: '↗', tone: 'blue', title: 'API 데이터 슬롯이 준비되었습니다', detail: '박지호 · /api/dashboard/summary', time: '1시간 전' },
  { icon: '✦', tone: 'purple', title: '디자인 검토 의견이 반영되었습니다', detail: '한지민 · UI Shell v2', time: '어제' },
  { icon: '↻', tone: 'amber', title: '샘플 데이터가 갱신되었습니다', detail: 'System · 24개 레코드', time: '어제' },
];

const endpoints = [
  { method: 'GET', path: '/api/v1/health', description: '엣지 서버 상태와 기본 검사 모드 판단', state: 'Ready' },
  { method: 'POST', path: '/api/v1/analyze', description: '입력·대화 문맥 위험도 분석', state: 'Ready' },
  { method: 'GET', path: '/api/v1/model/info', description: 'AI 모델과 룰셋 버전 조회', state: 'Ready' },
  { method: 'GET', path: '/api/v1/ruleset', description: '현재 사기 탐지 규칙 조회', state: 'Ready' },
  { method: 'PUT', path: '/api/v1/ruleset', description: '관리자 권한으로 탐지 규칙 갱신', state: 'Ready' },
];

const viewCopy: Record<View, { eyebrow: string; title: string; description: string }> = {
  overview: { eyebrow: 'WORKSPACE OVERVIEW', title: '좋은 아침이에요, 한별님.', description: '프로젝트의 흐름과 중요한 변화를 한곳에서 확인하세요.' },
  reports: { eyebrow: 'REPORT LIBRARY', title: '보고서', description: '진행 중인 보고서를 찾고 상태와 담당자를 관리하세요.' },
  data: { eyebrow: 'BACKEND CONTRACT', title: 'Data Map', description: '화면의 데이터 슬롯과 연결될 API 계약을 확인하세요.' },
  activity: { eyebrow: 'TEAM HISTORY', title: '활동 로그', description: '프로젝트에서 일어난 주요 변경을 시간순으로 확인하세요.' },
  settings: { eyebrow: 'WORKSPACE CONTROL', title: '환경 설정', description: '현재 앱의 동작 방식과 연동 환경을 관리하세요.' },
};

function App() {
  const [theme, setTheme] = useState<Theme>(() => window.localStorage.getItem('han2-theme') === 'dark' ? 'dark' : 'light');
  const [fontSize, setFontSize] = useState<FontSize>(() => {
    const saved = window.localStorage.getItem('han2-font-size');
    return saved === 'small' || saved === 'large' ? saved : 'medium';
  });
  const [securityEnabled, setSecurityEnabled] = useState(() => window.localStorage.getItem('han2-security') === 'on');
  const [showSecurityConsent, setShowSecurityConsent] = useState(() => window.localStorage.getItem('han2-security-consent') !== 'completed');
  const [view, setView] = useState<View>('overview');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<'전체' | ReportStatus>('전체');
  const [selectedId, setSelectedId] = useState(reports[0].id);
  const [synced, setSynced] = useState(false);
  const edgeHealth = useEdgeHealth();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [screenSources, setScreenSources] = useState<ScreenSource[]>([]);
  const [showScreenPicker, setShowScreenPicker] = useState(false);
  const [screenStream, setScreenStream] = useState<MediaStream | null>(null);
  const [sharedSourceName, setSharedSourceName] = useState('');
  const [screenError, setScreenError] = useState('');
  const previewRef = useRef<HTMLVideoElement>(null);
  const selected = reports.find((report) => report.id === selectedId) ?? reports[0];
  const filtered = useMemo(() => reports.filter((report) => {
    const query = search.trim().toLowerCase();
    const matchesSearch = !query || `${report.title} ${report.owner} ${report.category} ${report.tags.join(' ')}`.toLowerCase().includes(query);
    return matchesSearch && (status === '전체' || report.status === status);
  }), [search, status]);

  const changeView = (next: View) => { setView(next); setSidebarOpen(false); };
  const content = viewCopy[view];

  const syncData = async () => {
    await edgeHealth.refresh();
    setSynced(true);
    globalThis.setTimeout(() => setSynced(false), 1800);
  };

  useEffect(() => {
    window.localStorage.setItem('han2-theme', theme);
    document.documentElement.style.colorScheme = theme;
  }, [theme]);

  useEffect(() => {
    window.localStorage.setItem('han2-font-size', fontSize);
  }, [fontSize]);

  useEffect(() => {
    window.localStorage.setItem('han2-security', securityEnabled ? 'on' : 'off');
  }, [securityEnabled]);

  useEffect(() => {
    if (previewRef.current) previewRef.current.srcObject = screenStream;
  }, [screenStream]);

  useEffect(() => () => {
    screenStream?.getTracks().forEach((track) => track.stop());
  }, [screenStream]);

  const openScreenPicker = async () => {
    setScreenError('');
    if (!securityEnabled) {
      setScreenError('화면 공유를 시작하려면 먼저 보안채팅을 활성화하세요.');
      return;
    }
    if (!platformCapabilities.desktopScreenShare || !window.frontendHan2) {
      setScreenError(platformCapabilities.nativeMobile
        ? '모바일 화면 공유는 운영체제 권한 연결 후 사용할 수 있습니다.'
        : '화면 공유는 설치된 데스크톱 앱에서 사용할 수 있습니다.');
      return;
    }
    try {
      const sources = await window.frontendHan2.getScreenSources();
      setScreenSources(sources);
      setShowScreenPicker(true);
    } catch {
      setScreenError('공유 가능한 화면 목록을 불러오지 못했습니다.');
    }
  };

  const stopScreenShare = () => {
    screenStream?.getTracks().forEach((track) => track.stop());
    setScreenStream(null);
    setSharedSourceName('');
  };

  const startScreenShare = async (source: ScreenSource) => {
    try {
      stopScreenShare();
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          mandatory: {
            chromeMediaSource: 'desktop',
            chromeMediaSourceId: source.id,
            maxFrameRate: 10,
          },
        } as MediaTrackConstraints,
      });
      stream.getVideoTracks()[0]?.addEventListener('ended', stopScreenShare, { once: true });
      setScreenStream(stream);
      setSharedSourceName(source.name);
      setShowScreenPicker(false);
      setScreenError('');
    } catch {
      setScreenError('화면 공유를 시작하지 못했습니다. 운영체제의 화면 녹화 권한을 확인하세요.');
      setShowScreenPicker(false);
    }
  };

  const completeSecurityConsent = (allowed: boolean) => {
    setSecurityEnabled(allowed);
    window.localStorage.setItem('han2-security-consent', 'completed');
    setShowSecurityConsent(false);
  };

  return (
    <div className="app-shell" data-theme={theme} data-font-size={fontSize}>
      <aside className={`sidebar ${sidebarOpen ? 'open' : ''}`}>
        <div className="brand"><span className="brand-symbol">H</span><div><strong>HAN2</strong><small>DATA WORKSPACE</small></div></div>
        <nav className="main-nav" aria-label="메인 메뉴">
          <span className="nav-label">WORKSPACE</span>
          {navigation.map((item) => <button key={item.key} className={view === item.key ? 'active' : ''} onClick={() => changeView(item.key)}><i>{item.icon}</i><span><strong>{item.label}</strong><small>{item.caption}</small></span></button>)}
        </nav>
        <div className="workspace-card"><span className="pulse" /><div><strong>{env.useMocks ? 'Mock data mode' : edgeHealth.isDegraded ? '기본 검사 모드' : 'Edge AI connected'}</strong><small>{edgeHealth.isLoading ? '서버 상태 확인 중' : edgeHealth.isDegraded ? '로컬 탐지는 계속 작동합니다' : '정밀 분석 서버 연결 완료'}</small></div><span>›</span></div>
        <div className="profile"><span>HB</span><div><strong>김한별</strong><small>Product manager</small></div><button aria-label="프로필 메뉴">•••</button></div>
      </aside>
      {sidebarOpen && <button className="scrim" aria-label="메뉴 닫기" onClick={() => setSidebarOpen(false)} />}

      <main className="main-panel">
        <header className="topbar">
          <button className="menu-button" onClick={() => setSidebarOpen(true)}>☰</button>
          <div className="breadcrumb"><span>HAN2</span><b>/</b><strong>{navigation.find((item) => item.key === view)?.label}</strong></div>
          <div className="top-actions"><button className="icon-button" aria-label="알림">♢<i /></button><button className={`sync-button ${synced ? 'done' : ''}`} onClick={() => void syncData()} disabled={edgeHealth.isLoading}><span>{synced ? '✓' : '↻'}</span>{synced ? '동기화 완료' : edgeHealth.isLoading ? '확인 중' : 'Sync data'}</button></div>
        </header>

        <div className="content">
          <section className="page-heading"><div><span className="eyebrow">{content.eyebrow}</span><h1>{content.title}</h1><p>{content.description}</p></div><div className="today"><span>오늘</span><strong>2026. 07. 21</strong></div></section>
          {view === 'overview' && <Overview onOpenReports={() => changeView('reports')} selected={selected} securityEnabled={securityEnabled} setSecurityEnabled={setSecurityEnabled} screenStream={screenStream} sharedSourceName={sharedSourceName} previewRef={previewRef} onStartScreenShare={openScreenPicker} onStopScreenShare={stopScreenShare} screenError={screenError} />}
          {view === 'reports' && <ReportsPage reports={filtered} selected={selected} search={search} status={status} setSearch={setSearch} setStatus={setStatus} setSelectedId={setSelectedId} />}
          {view === 'data' && <DataMap />}
          {view === 'activity' && <ActivityPage />}
          {view === 'settings' && <SettingsView theme={theme} setTheme={setTheme} fontSize={fontSize} setFontSize={setFontSize} />}
        </div>

        <footer className="statusbar"><span><i className={securityEnabled ? 'online' : 'offline'} /> Security {securityEnabled ? 'ON' : 'OFF'}</span><span>분석 서버 <b>{env.useMocks ? 'Mock' : edgeHealth.isDegraded ? 'Local fallback' : 'Online'}</b></span><span>화면 <b>{navigation.find((item) => item.key === view)?.label}</b></span><span>채팅 모드 <b>{securityEnabled ? '보안 채팅' : '일반 채팅'}</b></span><span>필터 <b>{status}</b></span><span>선택 <b>{selected.id}</b></span></footer>
      </main>
      {showSecurityConsent && <SecurityConsent onChoose={completeSecurityConsent} />}
      {showScreenPicker && <ScreenPicker sources={screenSources} onSelect={startScreenShare} onClose={() => setShowScreenPicker(false)} />}
    </div>
  );
}

function SecurityConsent({ onChoose }: { onChoose: (allowed: boolean) => void }) {
  return <div className="consent-backdrop" role="presentation"><section className="consent-dialog" role="dialog" aria-modal="true" aria-labelledby="consent-title" aria-describedby="consent-description"><span className="consent-mark">H</span><span className="consent-kicker">WELCOME TO HAN2</span><h2 id="consent-title">채팅 보안 기능을<br />실행할까요?</h2><p id="consent-description">보안 기능을 허용하면 채팅 중 개인정보와 사기 위험을 확인하고 필요한 피드백과 보호 알림을 제공합니다.</p><div className="consent-points"><span><i>✓</i><b>위험 메시지 실시간 감지</b></span><span><i>✓</i><b>안전한 채팅을 위한 피드백</b></span><span><i>✓</i><b>필요한 순간 보호 알림</b></span></div><div className="consent-notice"><i>i</i><span>지금 선택한 설정은 메인 화면에서 언제든지 변경할 수 있습니다.</span></div><div className="consent-actions"><button className="deny" onClick={() => onChoose(false)}>허용하지 않음</button><button className="allow" onClick={() => onChoose(true)}><span>✓</span> 허용하고 시작</button></div><small>선택하기 전까지 보안 기능은 실행되지 않습니다.</small></section></div>;
}

function Overview({ onOpenReports, selected, securityEnabled, setSecurityEnabled, screenStream, sharedSourceName, previewRef, onStartScreenShare, onStopScreenShare, screenError }: { onOpenReports: () => void; selected: Report; securityEnabled: boolean; setSecurityEnabled: (enabled: boolean) => void; screenStream: MediaStream | null; sharedSourceName: string; previewRef: React.RefObject<HTMLVideoElement>; onStartScreenShare: () => void; onStopScreenShare: () => void; screenError: string }) {
  const metrics = [
    { label: '전체 보고서', value: '24', change: '+3 이번 달', icon: '▤', tone: 'blue' },
    { label: '검토 대기', value: '07', change: '2개 긴급', icon: '◷', tone: 'amber' },
    { label: '완료율', value: '82%', change: '+12.4%', icon: '↗', tone: 'green' },
    { label: '연결 슬롯', value: '05', change: '2개 준비됨', icon: '⌘', tone: 'purple' },
  ];
  return <>
    <section className={`security-control ${securityEnabled ? 'enabled' : 'disabled'}`}>
      <div className="security-identity"><span className="security-shield">{securityEnabled ? '✓' : '—'}</span><div><span className="security-label">CHAT SECURITY SYSTEM</span><h2>{securityEnabled ? '보안 채팅이 실행 중입니다' : '일반 채팅 모드입니다'}</h2><p>{securityEnabled ? '메시지의 개인정보와 사기 위험을 확인하고 실시간 피드백을 제공합니다.' : '채팅 내용에 보안 분석이나 피드백이 적용되지 않습니다.'}</p></div></div>
      <div className="security-actions"><span className="mode-state"><i />{securityEnabled ? 'Protection active' : 'Protection paused'}</span><div className="power-switch" role="group" aria-label="채팅 보안 모드"><button className={securityEnabled ? 'active' : ''} onClick={() => setSecurityEnabled(true)}>ON</button><button className={!securityEnabled ? 'active' : ''} onClick={() => setSecurityEnabled(false)}>OFF</button></div></div>
      <div className="security-features"><span className={securityEnabled ? 'active' : ''}><i>◉</i><b>위험 감지</b><small>{securityEnabled ? '실시간 분석 중' : '사용 안 함'}</small></span><span className={securityEnabled ? 'active' : ''}><i>✦</i><b>채팅 피드백</b><small>{securityEnabled ? '피드백 활성화' : '사용 안 함'}</small></span><span className={securityEnabled ? 'active' : ''}><i>♢</i><b>보호 알림</b><small>{securityEnabled ? '알림 대기 중' : '사용 안 함'}</small></span></div>
    </section>
    <section className={`screen-share-panel panel ${screenStream ? 'sharing' : ''}`}>
      <div className="screen-share-copy">
        <span className="eyebrow">LIVE SCREEN</span>
        <h2>{screenStream ? '화면 공유 중' : '실시간 화면 분석 준비'}</h2>
        <p>{screenStream ? `${sharedSourceName} 화면을 앱 내부에서 미리 보고 있습니다. 현재 외부 전송이나 AI 분석은 하지 않습니다.` : '공유할 화면이나 창을 직접 선택하세요. 공유 중에는 언제든 즉시 중지할 수 있습니다.'}</p>
        {screenError && <strong className="screen-error">{screenError}</strong>}
        <div className="screen-share-actions">
          {!screenStream && <button className="screen-start" onClick={onStartScreenShare} disabled={!securityEnabled}>화면 선택 및 공유 시작</button>}
          {screenStream && <button className="screen-stop" onClick={onStopScreenShare}>공유 중지</button>}
          <span><i className={screenStream ? 'live' : ''} />{screenStream ? '화면 캡처 활성' : securityEnabled ? '공유 대기 중' : '보안채팅을 먼저 켜세요'}</span>
        </div>
      </div>
      <div className="screen-preview">
        {screenStream ? <video ref={previewRef} autoPlay muted playsInline /> : <div><b>화면 미리보기</b><span>선택한 화면이 여기에 표시됩니다.</span></div>}
      </div>
    </section>
    <section className="metrics">{metrics.map((metric) => <article key={metric.label} className={`metric ${metric.tone}`}><div><span>{metric.label}</span><strong>{metric.value}</strong><small>{metric.change}</small></div><i>{metric.icon}</i></article>)}</section>
    <section className="dashboard-grid">
      <article className="panel workflow-panel"><PanelHead label="WORKFLOW" title="이번 주 작업 흐름" action="전체 보기" onAction={onOpenReports} /><div className="workflow-bars"><div className="bar-labels"><span>초안 <b>4</b></span><span>검토 중 <b>7</b></span><span>승인 완료 <b>13</b></span></div><div className="stacked"><i style={{ width: '17%' }} /><i style={{ width: '29%' }} /><i style={{ width: '54%' }} /></div></div><div className="report-preview"><span className="doc-icon">▤</span><div><small>최근 선택 보고서</small><strong>{selected.title}</strong><span>{selected.owner} · {selected.updated}</span></div><StatusBadge status={selected.status} /></div></article>
      <article className="panel activity-panel"><PanelHead label="RECENT ACTIVITY" title="최근 활동" /><div className="activity-list">{activities.slice(0, 4).map((item) => <Activity key={item.title} {...item} />)}</div></article>
      <article className="panel contract-panel"><PanelHead label="DATA CONTRACT" title="백엔드 연결 준비" action="Data Map 열기" /><div className="contract-copy"><div className="orbit"><span>API</span><i /><i /><i /></div><div><strong>화면 구조는 준비되었습니다.</strong><p>샘플 데이터를 API 응답으로 교체하면 현재 UI 구조를 그대로 사용할 수 있어요.</p><div className="contract-stats"><span><b>5</b> endpoints</span><span><b>12</b> data slots</span><span><b>80%</b> ready</span></div></div></div></article>
    </section>
  </>;
}

function ScreenPicker({ sources, onSelect, onClose }: { sources: ScreenSource[]; onSelect: (source: ScreenSource) => void; onClose: () => void }) {
  return <div className="screen-picker-backdrop" role="presentation">
    <section className="screen-picker" role="dialog" aria-modal="true" aria-labelledby="screen-picker-title">
      <header><div><span>SHARE WITH HAN2</span><h2 id="screen-picker-title">공유할 화면을 선택하세요</h2><p>선택한 화면은 미리보기에만 사용되며 아직 외부로 전송되지 않습니다.</p></div><button onClick={onClose} aria-label="닫기">×</button></header>
      <div className="screen-source-grid">{sources.map((source) => <button key={source.id} onClick={() => onSelect(source)}><img src={source.thumbnail} alt="" /><span>{source.name}</span></button>)}</div>
      {sources.length === 0 && <p className="screen-empty">공유 가능한 화면을 찾지 못했습니다.</p>}
    </section>
  </div>;
}

function ReportsPage({ reports: visible, selected, search, status, setSearch, setStatus, setSelectedId }: { reports: Report[]; selected: Report; search: string; status: '전체' | ReportStatus; setSearch: (value: string) => void; setStatus: (value: '전체' | ReportStatus) => void; setSelectedId: (value: string) => void }) {
  return <section className="reports-layout">
    <div className="panel report-list-panel"><div className="report-tools"><label><span>⌕</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="보고서, 담당자, 태그 검색" /></label><div className="filter-row">{(['전체', '완료', '검토 중', '초안'] as const).map((item) => <button key={item} className={status === item ? 'active' : ''} onClick={() => setStatus(item)}>{item}</button>)}</div></div><div className="table-head"><span>보고서</span><span>담당자</span><span>진행률</span><span>상태</span></div><div className="report-rows">{visible.map((report) => <button key={report.id} className={selected.id === report.id ? 'selected' : ''} onClick={() => setSelectedId(report.id)}><span className="report-title"><i>▤</i><span><strong>{report.title}</strong><small>{report.id} · {report.category}</small></span></span><span className="owner"><i>{report.owner.slice(-2)}</i>{report.owner}</span><span className="row-progress"><i><b style={{ width: `${report.progress}%` }} /></i>{report.progress}%</span><StatusBadge status={report.status} /></button>)}</div>{visible.length === 0 && <div className="empty">검색 조건에 맞는 보고서가 없습니다.</div>}</div>
    <aside className="panel detail-panel"><span className="detail-kicker">REPORT DETAIL</span><div className="detail-title"><i>▤</i><div><small>{selected.id}</small><h2>{selected.title}</h2></div></div><p>{selected.description}</p><div className="detail-progress"><span>전체 진행률 <b>{selected.progress}%</b></span><i><b style={{ width: `${selected.progress}%` }} /></i></div><dl><div><dt>상태</dt><dd><StatusBadge status={selected.status} /></dd></div><div><dt>카테고리</dt><dd>{selected.category}</dd></div><div><dt>담당자</dt><dd>{selected.owner}</dd></div><div><dt>최근 수정</dt><dd>{selected.updated}</dd></div></dl><div className="tags"><span>태그</span><div>{selected.tags.map((tag) => <b key={tag}>#{tag}</b>)}</div></div><button className="primary-action">보고서 열기 <span>↗</span></button></aside>
  </section>;
}

function DataMap() { return <section className="data-layout"><article className="panel endpoint-panel"><PanelHead label="ENDPOINTS" title="API 연결 지점" /><div className="endpoint-list">{endpoints.map((endpoint) => <div key={`${endpoint.method}-${endpoint.path}`}><b>{endpoint.method}</b><code>{endpoint.path}</code><span>{endpoint.description}</span><i className={endpoint.state.toLowerCase()}>{endpoint.state}</i></div>)}</div></article><article className="panel schema-panel"><PanelHead label="RESPONSE MODEL" title="위험 분석 응답 계약" /><pre><span>{'{'}</span>{'\n  '}<b>"risk_level"</b>: <em>"safe | caution | danger"</em>,{'\n  '}<b>"risk_score"</b>: <i>0.82</i>,{'\n  '}<b>"recommended_action"</b>: <em>"block_and_confirm"</em>,{'\n  '}<b>"detected_personal_info"</b>: [],{'\n  '}<b>"detected_fraud_patterns"</b>: [],{'\n  '}<b>"analysis_meta"</b>: {'{'} <b>"analysis_source"</b>: <em>"edge"</em> {'}'}{'\n'}<span>{'}'}</span></pre></article></section> }

function ActivityPage() { return <section className="panel timeline-panel"><div className="timeline-date">2026년 7월 21일 <span>오늘</span></div>{activities.map((item) => <Activity key={item.title} {...item} expanded />)}<div className="timeline-date muted">2026년 7월 20일</div>{activities.slice(1).reverse().map((item) => <Activity key={`old-${item.title}`} {...item} time="어제" expanded />)}</section> }

function SettingsView(props: { theme: Theme; setTheme: (theme: Theme) => void; fontSize: FontSize; setFontSize: (size: FontSize) => void }) {
  return <div className="settings-view"><UpdatePanel /><SettingsPage {...props} /></div>;
}

function UpdatePanel() {
  const initialVersion = window.frontendHan2?.version ?? '0.1.3';
  const [update, setUpdate] = useState<UpdateState>({
    status: 'idle',
    currentVersion: initialVersion,
    latestVersion: null,
    percent: 0,
    message: '최신 버전을 확인해 주세요.',
  });

  useEffect(() => {
    const api = window.frontendHan2;
    if (!api) {
      setUpdate((state) => ({
        ...state,
        status: 'latest',
        message: platformCapabilities.nativeMobile
          ? `${appPlatform === 'android' ? 'Google Play' : 'App Store'}에서 앱 업데이트를 관리합니다.`
          : '웹 버전은 항상 최신 상태로 제공됩니다.',
      }));
      return;
    }
    api.getUpdateState().then(setUpdate).catch(() => {});
    const unsubscribe = api.onUpdateState(setUpdate);
    return unsubscribe;
  }, []);

  const check = async () => {
    const api = window.frontendHan2;
    if (!api) return;
    setUpdate((state) => ({ ...state, status: 'checking', message: '최신 버전을 확인하고 있습니다.' }));
    try {
      setUpdate(await api.checkForUpdates());
    } catch {
      setUpdate((state) => ({ ...state, status: 'error', message: '업데이트 서버에 연결하지 못했습니다.' }));
    }
  };

  const install = async () => {
    const api = window.frontendHan2;
    if (!api) return;
    try {
      await api.installUpdate();
    } catch {
      setUpdate((state) => ({ ...state, status: 'error', message: '업데이트 다운로드 또는 설치에 실패했습니다.' }));
    }
  };

  const busy = update.status === 'checking' || update.status === 'downloading';
  const canInstall = update.status === 'available' || update.status === 'downloaded';
  return <article className={`panel update-panel ${canInstall ? 'has-update' : ''}`}>
    <div className="update-icon" aria-hidden="true">↻</div>
    <div className="update-copy">
      <span className="eyebrow">SOFTWARE UPDATE</span>
      <h2>{platformDisplayName} 업데이트</h2>
      <p>{update.message}</p>
      {update.status === 'downloading' && <div className="update-progress"><i style={{ width: `${update.percent}%` }} /></div>}
    </div>
    <div className="update-versions">
      <span><small>현재 버전</small><strong>{platformDisplayName} {update.currentVersion}</strong></span>
      <span><small>최신 버전</small><strong>{update.latestVersion ? `${platformDisplayName} ${update.latestVersion}` : '확인 필요'}</strong></span>
    </div>
    <div className="update-actions">
      {platformCapabilities.desktopAutoUpdate && (canInstall
        ? <button className="update-primary" onClick={install}>{update.status === 'downloaded' ? '재시작 후 설치' : '업데이트하기'}</button>
        : <button className="update-check" onClick={check} disabled={busy}>{busy ? '확인 중…' : '업데이트 확인'}</button>)}
      {update.status === 'latest' && <small className="latest-badge">✓ 최신 버전</small>}
    </div>
  </article>;
}

function SettingsPage({ theme, setTheme, fontSize, setFontSize }: { theme: Theme; setTheme: (theme: Theme) => void; fontSize: FontSize; setFontSize: (size: FontSize) => void }) { const [options, setOptions] = useState([true, true, false]); const fontOptions: { key: FontSize; label: string; sample: string }[] = [{ key: 'small', label: '작게', sample: '가' }, { key: 'medium', label: '기본', sample: '가' }, { key: 'large', label: '크게', sample: '가' }]; return <section className="settings-grid"><div className="settings-column"><article className="panel theme-panel"><PanelHead label="APPEARANCE" title="화면 테마" /><p>눈에 편한 화면 모드를 선택하세요. 설정은 다음 실행에도 유지됩니다.</p><div className="theme-options"><button className={theme === 'light' ? 'selected' : ''} onClick={() => setTheme('light')}><span className="theme-preview light-preview"><i /><b /><b /></span><span><strong>화이트 모드</strong><small>밝고 선명한 기본 화면</small></span><i className="radio" /></button><button className={theme === 'dark' ? 'selected' : ''} onClick={() => setTheme('dark')}><span className="theme-preview dark-preview"><i /><b /><b /></span><span><strong>다크 모드</strong><small>빛이 적은 편안한 화면</small></span><i className="radio" /></button></div></article><article className="panel font-panel"><PanelHead label="READABILITY" title="글자 크기" /><p>화면에서 가장 편하게 읽히는 크기를 선택하세요.</p><div className="font-options">{fontOptions.map((option) => <button key={option.key} className={fontSize === option.key ? `selected ${option.key}` : option.key} onClick={() => setFontSize(option.key)}><b>{option.sample}</b><span>{option.label}</span><i /></button>)}</div><div className="font-sample"><span>미리보기</span><strong>프로젝트의 중요한 변화를 한눈에 확인하세요.</strong></div></article><article className="panel setting-panel"><PanelHead label="GENERAL" title="일반 설정" />{['Mock data mode', 'Responsive layout', '상태 변경 알림'].map((label, index) => <button key={label} onClick={() => setOptions(options.map((value, i) => i === index ? !value : value))}><span><strong>{label}</strong><small>{index === 0 ? '샘플 데이터로 화면을 구성합니다.' : index === 1 ? '화면 크기에 맞춰 레이아웃을 조정합니다.' : '보고서 상태가 바뀌면 알려드립니다.'}</small></span><i className={options[index] ? 'on' : ''} /></button>)}</article></div><article className="panel environment-card"><PanelHead label="ENVIRONMENT" title="연동 환경" /><dl><div><dt>Theme</dt><dd>{theme === 'light' ? 'White' : 'Dark'}</dd></div><div><dt>Font size</dt><dd>{fontSize === 'small' ? 'Small' : fontSize === 'large' ? 'Large' : 'Default'}</dd></div><div><dt>Mode</dt><dd>Development</dd></div><div><dt>API Version</dt><dd>v1 draft</dd></div><div><dt>Last sync</dt><dd>아직 연결되지 않음</dd></div><div><dt>Contract</dt><dd className="success-text">Ready</dd></div></dl><button className="outline-action">연동 설정 확인</button></article></section> }

function PanelHead({ label, title, action, onAction }: { label: string; title: string; action?: string; onAction?: () => void }) { return <header className="panel-head"><div><span>{label}</span><h2>{title}</h2></div>{action && <button onClick={onAction}>{action} <b>→</b></button>}</header> }
function StatusBadge({ status }: { status: ReportStatus }) { return <span className={`status-badge ${status === '완료' ? 'complete' : status === '검토 중' ? 'review' : 'draft'}`}><i />{status}</span> }
function Activity({ icon, tone, title, detail, time, expanded = false }: { icon: string; tone: string; title: string; detail: string; time: string; expanded?: boolean }) { return <div className={`activity ${expanded ? 'expanded' : ''}`}><i className={tone}>{icon}</i><div><strong>{title}</strong><span>{detail}</span></div><time>{time}</time></div> }

export default App;
