import { useEffect, useMemo, useState } from 'react';
import { Capacitor } from '@capacitor/core';
import './styles.css';
import { KeyboardSetup, type DetectionReport, type KeyboardStatus } from './platform/keyboardSetup';

type Settings = {
  keyboard: boolean;
  protection: boolean;
  suggestions: boolean;
  haptics: boolean;
  sound: boolean;
  numberRow: boolean;
  theme: 'system' | 'light' | 'dark';
};

const initialSettings: Settings = {
  keyboard: true,
  protection: true,
  suggestions: true,
  haptics: true,
  sound: false,
  numberRow: false,
  theme: 'system',
};

const Icon = ({ name }: { name: string }) => {
  const paths: Record<string, React.ReactNode> = {
    keyboard: <><rect x="3" y="6" width="18" height="12" rx="2"/><path d="M7 10h.01M11 10h.01M15 10h.01M19 10h.01M7 14h.01M17 14h.01M10 14h4"/></>,
    shield: <><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/></>,
    spark: <><path d="m12 3-1.5 4.5L6 9l4.5 1.5L12 15l1.5-4.5L18 9l-4.5-1.5L12 3Z"/><path d="m5 15-.8 2.2L2 18l2.2.8L5 21l.8-2.2L8 18l-2.2-.8L5 15Z"/></>,
    vibration: <><path d="M8 6h8v12H8z"/><path d="M4 8v8M2 10v4M20 8v8M22 10v4"/></>,
    volume: <><path d="M11 5 6 9H2v6h4l5 4V5Z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12"/></>,
    numbers: <><path d="M5 4 3 20M11 4 9 20M2 9h12M1 15h12"/><path d="M17 7h4l-4 5h4v5h-4"/></>,
    palette: <><circle cx="12" cy="12" r="9"/><circle cx="8.5" cy="9" r=".5"/><circle cx="12" cy="7" r=".5"/><circle cx="15.5" cy="9" r=".5"/><path d="M15 16c0 1.2 1 2 2.2 1.7A9 9 0 1 0 12 21"/></>,
    info: <><circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/></>,
  };
  return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
};

function Toggle({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return <button className={`toggle ${checked ? 'on' : ''}`} role="switch" aria-checked={checked} aria-label={label} onClick={onChange}><span /></button>;
}

function App() {
  const [settings, setSettings] = useState<Settings>(() => {
    try { return { ...initialSettings, ...JSON.parse(localStorage.getItem('han2-keyboard-settings') || '{}') }; }
    catch { return initialSettings; }
  });
  const [sample, setSample] = useState('오늘도 안전하게 입력하세요');
  const isAndroid = Capacitor.getPlatform() === 'android';
  const [keyboardStatus, setKeyboardStatus] = useState<KeyboardStatus>({ enabled: false, selected: false });
  const [setupError, setSetupError] = useState('');
  const [showReports, setShowReports] = useState(false);
  const [reports, setReports] = useState<DetectionReport[]>([]);
  const safety = useMemo(() => {
    if (/(주민|비밀번호|OTP|인증번호).*[0-9]{4,}|[0-9]{6}-?[0-9]{7}/i.test(sample)) return { level: 'danger', title: '위험 · 민감정보가 포함됐어요', detail: '번호를 삭제하거나 가린 뒤 보내세요.' };
    if (/(송금|입금|계좌|상품권|대출)|http:\/\/|bit\.ly|tinyurl/i.test(sample)) return { level: 'warning', title: '주의 · 금융사기 가능성을 확인하세요', detail: '상대방과 링크를 다시 확인한 뒤 보내세요.' };
    if (/(죽여|협박|가만두지|자해|죽고 싶)/.test(sample)) return { level: 'danger', title: '위험 · 심각한 표현이 감지됐어요', detail: '보내기 전에 문장을 다시 확인하세요.' };
    if (/01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}/.test(sample)) return { level: 'warning', title: '주의 · 개인정보로 보이는 번호예요', detail: '공유해도 되는 정보인지 확인하세요.' };
    return { level: 'safe', title: '감지 중', detail: '' };
  }, [sample]);

  useEffect(() => { localStorage.setItem('han2-keyboard-settings', JSON.stringify(settings)); }, [settings]);
  useEffect(() => {
    if (!isAndroid) return;
    void KeyboardSetup.setKeyboardPreferences({
      protection: settings.protection,
      haptics: settings.haptics,
      sound: settings.sound,
      numberRow: settings.numberRow,
      theme: settings.theme,
    }).catch(() => setSetupError('키보드 설정을 Android에 반영하지 못했습니다.'));
  }, [isAndroid, settings.protection, settings.haptics, settings.sound, settings.numberRow, settings.theme]);
  const toggle = (key: keyof Omit<Settings, 'theme'>) => setSettings((prev) => ({ ...prev, [key]: !prev[key] }));
  const changeProtection = async () => {
    const enabled = !settings.protection;
    setSettings((prev) => ({ ...prev, protection: enabled }));
    if (isAndroid) {
      try { await KeyboardSetup.setProtectionEnabled({ enabled }); }
      catch { setSetupError('보호 설정을 키보드에 전달하지 못했습니다.'); }
    }
  };
  const refreshKeyboardStatus = async () => {
    if (!isAndroid) return;
    try { setKeyboardStatus(await KeyboardSetup.getStatus()); setSetupError(''); }
    catch { setSetupError('키보드 상태를 확인하지 못했습니다.'); }
  };
  useEffect(() => {
    void refreshKeyboardStatus();
    const refresh = () => { if (document.visibilityState === 'visible') void refreshKeyboardStatus(); };
    document.addEventListener('visibilitychange', refresh);
    window.addEventListener('focus', refresh);
    return () => { document.removeEventListener('visibilitychange', refresh); window.removeEventListener('focus', refresh); };
  }, [isAndroid]);
  const setupKeyboard = async () => {
    if (!isAndroid) { toggle('keyboard'); return; }
    try {
      setSetupError('');
      if (!keyboardStatus.enabled) await KeyboardSetup.openKeyboardSettings();
      else await KeyboardSetup.showKeyboardPicker();
    } catch (error) { setSetupError(error instanceof Error ? error.message : '시스템 키보드 설정을 열지 못했습니다.'); }
  };
  const openReports = async () => {
    setShowReports(true);
    if (!isAndroid) return;
    try { setReports((await KeyboardSetup.getDetectionReports()).reports); }
    catch { setSetupError('감지 보고서를 불러오지 못했습니다.'); }
  };
  const clearReports = async () => {
    if (isAndroid) await KeyboardSetup.clearDetectionReports();
    setReports([]);
  };

  return <main className="settings-app">
    <div className="phone-page">
      <header className="app-header">
        <div><h1>키보드 설정</h1><p>입력에 필요한 기능만 간편하게 관리하세요.</p></div>
        <div className={`status-chip ${(isAndroid ? keyboardStatus.selected : settings.keyboard) ? 'active' : ''}`}><i />{isAndroid ? keyboardStatus.selected ? '기본 키보드' : keyboardStatus.enabled ? '선택 필요' : '설정 필요' : settings.keyboard ? '사용 중' : '꺼짐'}</div>
      </header>

      <section className="hero-card">
        <div className="hero-copy"><span className="hero-icon"><Icon name="keyboard" /></span><div><strong>HAN2 키보드</strong><small>{isAndroid ? keyboardStatus.selected ? '다른 앱에서도 사용할 수 있어요' : keyboardStatus.enabled ? 'HAN2를 기본 키보드로 선택해 주세요' : '시스템에서 키보드를 추가해 주세요' : settings.keyboard ? '지금 바로 사용할 수 있어요' : '키보드가 꺼져 있어요'}</small></div></div>
        {isAndroid ? <button className="setup-button" onClick={() => void setupKeyboard()}>{keyboardStatus.selected ? '변경' : keyboardStatus.enabled ? '선택하기' : '키보드 추가'}</button> : <Toggle checked={settings.keyboard} onChange={() => toggle('keyboard')} label="HAN2 키보드" />}
      </section>
      {isAndroid && !keyboardStatus.selected && <section className="setup-guide"><strong>{keyboardStatus.enabled ? '마지막 1단계가 남았어요' : '2단계로 키보드를 연결하세요'}</strong><div><span className={keyboardStatus.enabled ? 'done' : 'current'}>1</span><p><b>HAN2 안전 키보드 켜기</b><small>Android 키보드 목록에서 활성화</small></p></div><div><span className={keyboardStatus.enabled ? 'current' : ''}>2</span><p><b>기본 키보드로 선택</b><small>키보드 선택 창에서 HAN2 선택</small></p></div>{setupError && <em>{setupError}</em>}</section>}

      <section className="preview-section" aria-label="키보드 미리보기">
        <div className="section-title"><div><span>미리보기</span><h2>나의 키보드</h2></div><small>직접 입력해 보세요</small></div>
        <div className="keyboard-preview">
          <div className="input-preview"><input value={sample} onChange={(e) => setSample(e.target.value)} aria-label="키보드 테스트 입력" /><button onClick={() => setSample('')} aria-label="입력 내용 지우기">×</button></div>
          <div className={`safety-preview ${safety.level}`}><i /> <span><strong>{safety.title}</strong>{safety.detail && <small>{safety.detail}</small>}</span></div>
          {settings.suggestions && <div className="suggestions"><span>안녕하세요</span><span>감사합니다</span><span>좋은 하루 보내세요</span></div>}
          {settings.numberRow && <div className="key-row number-row">{'1234567890'.split('').map((key) => <kbd key={key}>{key}</kbd>)}</div>}
          <div className="key-row">{'ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ'.split('').map((key) => <kbd key={key}>{key}</kbd>)}</div>
          <div className="key-row inset">{'ㅁㄴㅇㄹㅎㅗㅓㅏㅣ'.split('').map((key) => <kbd key={key}>{key}</kbd>)}</div>
          <div className="key-row bottom"><kbd className="wide">⇧</kbd>{'ㅋㅌㅊㅍㅠㅜㅡ'.split('').map((key) => <kbd key={key}>{key}</kbd>)}<kbd className="wide">⌫</kbd></div>
          <div className="key-row utility"><kbd>123</kbd><kbd>☺</kbd><kbd className="space">space</kbd><kbd>return</kbd></div>
        </div>
      </section>

      <section className="setting-group">
        <h2>핵심 기능</h2>
        <SettingRow icon="shield" color="green" title="안전 입력 보호" description="앱을 닫아도 키보드에서 계속 감지해요" checked={settings.protection} onChange={() => void changeProtection()} />
        <SettingRow icon="spark" color="purple" title="문장 추천" description="입력에 맞는 다음 문장을 보여줘요" checked={settings.suggestions} onChange={() => toggle('suggestions')} />
        <SettingRow icon="vibration" color="blue" title="키 입력 진동" description="키를 누를 때 가볍게 진동해요" checked={settings.haptics} onChange={() => toggle('haptics')} />
        <SettingRow icon="volume" color="orange" title="키 입력 소리" description="키를 누를 때 소리를 재생해요" checked={settings.sound} onChange={() => toggle('sound')} />
      </section>

      <section className="setting-group">
        <h2>키보드 모양</h2>
        <SettingRow icon="numbers" color="gray" title="숫자 행" description="키보드 위에 숫자 키를 항상 표시해요" checked={settings.numberRow} onChange={() => toggle('numberRow')} />
        <div className="setting-row theme-row"><span className="row-icon pink"><Icon name="palette" /></span><div className="row-copy"><strong>테마</strong><small>키보드 화면 스타일</small></div><select value={settings.theme} onChange={(e) => setSettings((prev) => ({ ...prev, theme: e.target.value as Settings['theme'] }))} aria-label="키보드 테마"><option value="system">시스템</option><option value="light">라이트</option><option value="dark">다크</option></select></div>
      </section>

      <section className="setting-group compact"><div className="setting-row"><span className="row-icon gray"><Icon name="info" /></span><div className="row-copy"><strong>HAN2 키보드 정보</strong><small>버전 0.1.4 · 개인정보 보호 안내</small></div><span className="chevron">›</span></div></section>
      <button className="report-entry" onClick={() => void openReports()}><span className="report-entry-icon">▤</span><span><strong>감지 보고서</strong><small>감지된 내용과 판단 이유를 확인하세요</small></span><b>보기 ›</b></button>
      <footer>설정은 이 기기에 안전하게 저장됩니다.</footer>
      {showReports && <ReportDialog reports={reports} onClose={() => setShowReports(false)} onClear={() => void clearReports()} />}
    </div>
  </main>;
}

function ReportDialog({ reports, onClose, onClear }: { reports: DetectionReport[]; onClose: () => void; onClear: () => void }) {
  return <div className="report-backdrop" role="presentation" onClick={onClose}><section className="report-dialog" role="dialog" aria-modal="true" aria-labelledby="report-title" onClick={(e) => e.stopPropagation()}><header><div><span>HAN2 SAFETY REPORT</span><h2 id="report-title">감지 보고서</h2><p>원문 전체는 저장하지 않고 민감한 숫자는 가려서 표시합니다.</p></div><button onClick={onClose} aria-label="보고서 닫기">×</button></header><div className="report-summary"><div><strong>{reports.length}</strong><small>전체 감지</small></div><div><strong>{reports.filter((report) => report.level === 'danger').length}</strong><small>위험</small></div><div><strong>{reports.filter((report) => report.level === 'warning').length}</strong><small>주의</small></div></div><div className="report-list">{reports.length === 0 ? <div className="report-empty"><span>✓</span><strong>아직 감지된 위험이 없어요</strong><small>키보드에서 주의·위험이 감지되면 여기에 표시됩니다.</small></div> : reports.map((report) => <article key={report.id} className={report.level}><div className="report-item-head"><span>{report.level === 'danger' ? '위험' : '주의'}</span><time>{new Date(report.detectedAt).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</time></div><h3>{report.title}</h3><p>{report.preview}</p><small><b>감지 이유</b>{report.reason}</small></article>)}</div><footer><button onClick={onClear} disabled={reports.length === 0}>기록 비우기</button><button className="close-report" onClick={onClose}>확인</button></footer></section></div>;
}

function SettingRow({ icon, color, title, description, checked, onChange }: { icon: string; color: string; title: string; description: string; checked: boolean; onChange: () => void }) {
  return <div className="setting-row"><span className={`row-icon ${color}`}><Icon name={icon} /></span><div className="row-copy"><strong>{title}</strong><small>{description}</small></div><Toggle checked={checked} onChange={onChange} label={title} /></div>;
}

export default App;
