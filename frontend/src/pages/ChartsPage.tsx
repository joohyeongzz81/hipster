import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Grid, Typography, CircularProgress, Alert } from '@mui/material';
import { getTopChart } from '../api/charts';
import { ChartFilter } from '../types';
import ReleaseCard from '../components/ReleaseCard';
import FilterSidebar from '../components/FilterSidebar';

export default function ChartsPage() {
  const [filter, setFilter] = useState<ChartFilter>({ limit: 100 });

  const { data, isLoading, error } = useQuery({
    queryKey: ['topChart', filter],
    queryFn: () => getTopChart(filter),
  });

  return (
    <Box>
      <Typography variant="h4" gutterBottom fontWeight="bold">
        {data?.chartType || 'Top Charts'}
      </Typography>
      
      {data?.lastUpdated && (
        <Typography variant="subtitle2" color="text.secondary" gutterBottom>
          Last updated: {new Date(data.lastUpdated).toLocaleString()}
        </Typography>
      )}

      <Grid container spacing={3} sx={{ mt: 1 }}>
        <Grid item xs={12} md={3}>
          <FilterSidebar onFilterChange={setFilter} />
        </Grid>

        <Grid item xs={12} md={9}>
          {isLoading && (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 5 }}>
              <CircularProgress />
            </Box>
          )}

          {error && (
            <Alert severity="error">Failed to load chart data.</Alert>
          )}

          {data?.entries.map((entry) => (
            <ReleaseCard key={entry.releaseId} entry={entry} />
          ))}
          
          {!isLoading && data?.entries.length === 0 && (
            <Alert severity="info">No releases found matching your criteria.</Alert>
          )}
        </Grid>
      </Grid>
    </Box>
  );
}
