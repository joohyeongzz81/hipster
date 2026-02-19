import { Routes, Route, Link } from 'react-router-dom';
import { CssBaseline, ThemeProvider, createTheme, Box, AppBar, Toolbar, Typography, Container, Button } from '@mui/material';
import ChartsPage from './pages/ChartsPage';
import ReleasePage from './pages/ReleasePage';

const darkTheme = createTheme({
  palette: {
    mode: 'dark',
    primary: {
      main: '#90caf9',
    },
    background: {
      default: '#121212',
      paper: '#1e1e1e',
    },
  },
});

function App() {
  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <Box sx={{ flexGrow: 1 }}>
        <AppBar position="static" color="default">
          <Toolbar>
            <Typography variant="h6" component={Link} to="/" sx={{ flexGrow: 1, textDecoration: 'none', color: 'inherit', fontWeight: 'bold' }}>
              RYM Clone
            </Typography>
            <Button color="inherit" component={Link} to="/charts">Charts</Button>
            <Button color="inherit">Login</Button>
          </Toolbar>
        </AppBar>
        <Container sx={{ mt: 4 }}>
          <Routes>
            <Route path="/" element={<ChartsPage />} />
            <Route path="/charts" element={<ChartsPage />} />
            <Route path="/release/:id" element={<ReleasePage />} />
          </Routes>
        </Container>
      </Box>
    </ThemeProvider>
  );
}

export default App;
