import { FormControl, InputLabel, Select, MenuItem } from '@mui/material';
import { Language } from '../../utils/types';

interface LanguageSelectorProps {
  value: Language;
  onChange: (language: Language) => void;
}

export default function LanguageSelector({ value, onChange }: LanguageSelectorProps) {
  return (
    <FormControl size="small" sx={{ minWidth: 120 }}>
      <InputLabel>Language</InputLabel>
      <Select
        value={value}
        label="Language"
        onChange={(e) => onChange(e.target.value as Language)}
      >
        <MenuItem value="JAVA">Java</MenuItem>
      </Select>
    </FormControl>
  );
}
