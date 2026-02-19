import { useParams } from 'react-router-dom';
import { Box, Typography, Container, Divider, Rating } from '@mui/material';
import { useState } from 'react';

export default function ReleasePage() {
  const { id } = useParams();
  const [rating, setRating] = useState<number | null>(0);

  return (
    <Container maxWidth="md">
      <Box sx={{ display: 'flex', gap: 4, my: 4 }}>
        <Box sx={{ width: 250, height: 250, bgcolor: 'grey.800', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Typography color="grey.500">Cover Art</Typography>
        </Box>

        <Box>
          <Typography variant="h3" fontWeight="bold">Release Title (ID: {id})</Typography>
          <Typography variant="h5" color="text.secondary">Artist Name</Typography>
          <Typography variant="subtitle1" sx={{ mt: 1 }}>1997 • Album • Rock</Typography>
          
          <Box sx={{ mt: 3 }}>
            <Typography component="legend">Your Rating</Typography>
            <Rating
              name="simple-controlled"
              value={rating}
              precision={0.5}
              onChange={(_, newValue) => {
                setRating(newValue);
              }}
              size="large"
            />
          </Box>
        </Box>
      </Box>

      <Divider sx={{ my: 4 }} />

      <Typography variant="h5" gutterBottom>Track Listing</Typography>
      <Typography color="text.secondary">Tracks implementation coming soon...</Typography>
    </Container>
  );
}
