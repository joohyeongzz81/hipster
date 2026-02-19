import { Card, CardContent, Typography, Box, Chip, Rating } from '@mui/material';
import { ChartEntry } from '../types';
import { useNavigate } from 'react-router-dom';

interface Props {
  entry: ChartEntry;
}

export default function ReleaseCard({ entry }: Props) {
  const navigate = useNavigate();

  return (
    <Card 
      sx={{ display: 'flex', mb: 2, cursor: 'pointer', '&:hover': { bgcolor: 'action.hover' } }}
      onClick={() => navigate(`/release/${entry.releaseId}`)}
    >
      {/* Placeholder Image */}
      <Box sx={{ width: 100, height: 100, bgcolor: 'grey.800', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <Typography variant="caption" color="grey.500">Cover</Typography>
      </Box>
      
      <CardContent sx={{ flex: '1 0 auto', py: 1 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <Box>
            <Typography component="div" variant="h6">
              {entry.rank}. {entry.title}
            </Typography>
            <Typography variant="subtitle1" color="text.secondary" component="div">
              {entry.artistName} ({entry.releaseYear})
            </Typography>
          </Box>
          <Box sx={{ textAlign: 'right' }}>
            <Typography variant="h5" color="primary.main" fontWeight="bold">
              {entry.bayesianScore.toFixed(2)}
            </Typography>
            <Typography variant="caption" color="text.secondary" display="block">
              {entry.totalRatings.toLocaleString()} ratings
            </Typography>
          </Box>
        </Box>
        
        <Box sx={{ display: 'flex', alignItems: 'center', mt: 1, gap: 1 }}>
          <Rating value={entry.weightedAvgRating} precision={0.1} readOnly size="small" />
          <Typography variant="caption">
            (Avg: {entry.weightedAvgRating.toFixed(2)})
          </Typography>
          {entry.isEsoteric && <Chip label="Esoteric" size="small" color="secondary" variant="outlined" />}
        </Box>
      </CardContent>
    </Card>
  );
}
