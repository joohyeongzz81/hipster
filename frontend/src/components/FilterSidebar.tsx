import { Box, TextField, FormControl, InputLabel, Select, MenuItem, FormControlLabel, Checkbox, Button, Typography, Paper } from '@mui/material';
import { ChartFilter } from '../types';
import { useState } from 'react';

interface Props {
  onFilterChange: (filter: ChartFilter) => void;
}

export default function FilterSidebar({ onFilterChange }: Props) {
  const [filter, setFilter] = useState<ChartFilter>({
    year: undefined,
    releaseType: '',
    includeEsoteric: false,
    limit: 100
  });

  const handleChange = (field: keyof ChartFilter, value: any) => {
    setFilter(prev => ({ ...prev, [field]: value }));
  };

  const handleApply = () => {
    const cleanFilter: ChartFilter = {};
    if (filter.year) cleanFilter.year = filter.year;
    if (filter.releaseType) cleanFilter.releaseType = filter.releaseType;
    if (filter.includeEsoteric) cleanFilter.includeEsoteric = true;
    if (filter.limit) cleanFilter.limit = filter.limit;
    
    onFilterChange(cleanFilter);
  };

  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="h6" gutterBottom>Filters</Typography>
      
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <TextField
          label="Year"
          type="number"
          value={filter.year || ''}
          onChange={(e) => handleChange('year', e.target.value ? parseInt(e.target.value) : undefined)}
          size="small"
        />

        <FormControl size="small">
          <InputLabel>Type</InputLabel>
          <Select
            value={filter.releaseType || ''}
            label="Type"
            onChange={(e) => handleChange('releaseType', e.target.value)}
          >
            <MenuItem value="">All</MenuItem>
            <MenuItem value="ALBUM">Album</MenuItem>
            <MenuItem value="EP">EP</MenuItem>
            <MenuItem value="SINGLE">Single</MenuItem>
            <MenuItem value="COMPILATION">Compilation</MenuItem>
          </Select>
        </FormControl>

        <FormControlLabel
          control={
            <Checkbox
              checked={filter.includeEsoteric || false}
              onChange={(e) => handleChange('includeEsoteric', e.target.checked)}
            />
          }
          label="Include Esoteric"
        />

        <Button variant="contained" onClick={handleApply}>
          Update Chart
        </Button>
      </Box>
    </Paper>
  );
}
