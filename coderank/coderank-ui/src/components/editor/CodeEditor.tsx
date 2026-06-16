import Editor from '@monaco-editor/react';
import { useThemeStore } from '../../stores/themeStore';
import '../../styles/editor.css';

interface CodeEditorProps {
  value: string;
  onChange: (value: string) => void;
  language?: string;
  readOnly?: boolean;
}

export default function CodeEditor({ value, onChange, language = 'java', readOnly = false }: CodeEditorProps) {
  const darkMode = useThemeStore((s) => s.darkMode);

  return (
    <div className="monaco-editor-container">
      <Editor
        height="400px"
        language={language}
        theme={darkMode ? 'vs-dark' : 'light'}
        value={value}
        onChange={(val) => onChange(val ?? '')}
        options={{
          minimap: { enabled: false },
          fontSize: 14,
          automaticLayout: true,
          scrollBeyondLastLine: false,
          readOnly,
          bracketPairColorization: { enabled: true },
          padding: { top: 16 },
        }}
      />
    </div>
  );
}
